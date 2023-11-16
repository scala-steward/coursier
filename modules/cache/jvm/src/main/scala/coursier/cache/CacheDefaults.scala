package coursier.cache

import java.io.{File, FilenameFilter}
import java.net.URI
import java.nio.file.Path

import coursier.credentials.{Credentials, DirectCredentials, FileCredentials, Password}
import coursier.parse.{CachePolicyParser, CredentialsParser}
import coursier.paths.{CachePath, CoursierPaths}
import coursier.util.Sync

import scala.cli.config.{ConfigDb, Key, Keys, PasswordOption}
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.{Duration, DurationInt}
import scala.util.{Failure, Success, Try}

object CacheDefaults {

  lazy val location: File = CachePath.defaultCacheDirectory()

  lazy val archiveCacheLocation: File = CachePath.defaultArchiveCacheDirectory()

  @deprecated(
    "Legacy cache location support was dropped, this method does nothing.",
    "2.0.0-RC6-22"
  )
  def warnLegacyCacheLocation(): Unit = {}

  private def defaultConcurrentDownloadCount = 6

  lazy val concurrentDownloadCount: Int =
    sys.props
      .get("coursier.parallel-download-count")
      .flatMap(s => Try(s.toInt).toOption)
      .getOrElse(
        defaultConcurrentDownloadCount
      )

  lazy val pool = Sync.fixedThreadPool(concurrentDownloadCount)

  def parseDuration(s: String): Either[Throwable, Duration] =
    if (s.nonEmpty && s.forall(_ == '0'))
      Right(Duration.Zero)
    else
      Try(Duration(s)) match {
        case Success(s) => Right(s)
        case Failure(t) => Left(t)
      }

  lazy val ttl: Option[Duration] = {
    val fromEnv   = Option(System.getenv("COURSIER_TTL")).flatMap(parseDuration(_).toOption)
    def fromProps = sys.props.get("coursier.ttl").flatMap(parseDuration(_).toOption)
    def default   = 24.hours

    fromEnv
      .orElse(fromProps)
      .orElse(Some(default))
  }

  // Check SHA-1 if available, else be fine with no checksum
  val checksums = Seq(Some("SHA-1"), None)

  private def defaultSslRetryCount = 3

  lazy val sslRetryCount =
    sys.props
      .get("coursier.sslexception-retry")
      .flatMap(s => scala.util.Try(s.toInt).toOption)
      .filter(_ >= 0)
      .getOrElse(defaultSslRetryCount)

  private def defaultMaxRedirections = Option(20) // same default as java.net.HttpURLConnection
  lazy val maxRedirections: Option[Int] = {
    def prop(name: String) =
      sys.props
        .get(name)
        .flatMap(s => scala.util.Try(s.toInt).toOption)
        .filter(_ >= 0)
    prop("coursier.http.maxRedirects")
      .orElse(prop("http.maxRedirects")) // same property as java.net.HttpURLConnection
      .orElse(defaultMaxRedirections)
  }

  def defaultRetryCount = 1

  val bufferSize = 1024 * 1024

  private def credentialPropOpt =
    Option(System.getenv("COURSIER_CREDENTIALS"))
      .orElse(sys.props.get("coursier.credentials"))
      .map(s => s.dropWhile(_.isSpaceChar))

  private def isPropFile(s: String) =
    s.startsWith("/") || s.startsWith("file:")

  def credentials: Seq[Credentials] = {
    val legacyCredentials =
      if (credentialPropOpt.isEmpty) {
        // Warn if those files have group and others read permissions?
        val configDirs = coursier.paths.CoursierPaths.configDirectories().toSeq
        val mainCredentialsFiles =
          configDirs.map(configDir => new File(configDir, "credentials.properties"))
        val otherFiles = {
          // delay listing files until credentials are really needed?
          val dirs = configDirs.map(configDir => new File(configDir, "credentials"))
          val files = dirs.flatMap { dir =>
            val listOrNull = dir.listFiles { (dir, name) =>
              !name.startsWith(".") && name.endsWith(".properties")
            }
            Option(listOrNull).toSeq.flatten
          }
          Option(files).toSeq.flatten.map { f =>
            FileCredentials(f.getAbsolutePath, optional = true) // non optional?
          }
        }
        mainCredentialsFiles.map(f => FileCredentials(f.getAbsolutePath, optional = true)) ++
          otherFiles
      }
      else
        credentialPropOpt
          .toSeq
          .flatMap {
            case path if isPropFile(path) =>
              // hope Windows users can manage to use file:// URLs fine
              val path0 =
                if (path.startsWith("file:"))
                  new File(new URI(path)).getAbsolutePath
                else
                  path
              Seq(FileCredentials(path0, optional = true))
            case s =>
              CredentialsParser.parseSeq(s).either.toSeq.flatten
          }

    val configPath        = CoursierPaths.scalaConfigFile()
    val configCredentials = credentialsFromConfig(configPath)

    configCredentials ++ legacyCredentials
  }

  def credentialsFromConfig(configPath: Path): Seq[Credentials] = {
    val configDb = ConfigDb.open(configPath).fold(e => throw new Exception(e), identity)
    configDb
      .get(Keys.repositoryCredentials)
      .fold(e => throw new Exception(e), _.getOrElse(Nil))
      .map { cred =>
        DirectCredentials(
          host = cred.host,
          usernameOpt = cred.user.map(_.get().value),
          passwordOpt = cred.password.map(p => Password(p.get().value)),
          realm = cred.realm,
          optional = cred.optional.getOrElse(true),
          matchHost = cred.matchHost.getOrElse(DirectCredentials.defaultMatchHost),
          httpsOnly = cred.httpsOnly.getOrElse(DirectCredentials.defaultHttpsOnly),
          passOnRedirect = cred.passOnRedirect.getOrElse(false)
        )
      }
  }

  val noEnvCachePolicies = Seq(
    // first, try to update changing artifacts that were previously downloaded (follows TTL)
    CachePolicy.LocalUpdateChanging,
    // then, use what's available locally
    CachePolicy.LocalOnly,
    // lastly, try to download what's missing
    CachePolicy.Update
  )

  def cachePolicies: Seq[CachePolicy] = {

    def fromOption(value: Option[String], description: String): Option[Seq[CachePolicy]] =
      value.filter(_.nonEmpty).flatMap {
        str =>
          CachePolicyParser.cachePolicies(str, noEnvCachePolicies).either match {
            case Right(Seq()) =>
              Console.err.println(
                s"Warning: no mode found in $description, ignoring it."
              )
              None
            case Right(policies) =>
              Some(policies)
            case Left(_) =>
              Console.err.println(
                s"Warning: unrecognized mode in $description, ignoring it."
              )
              None
          }
      }

    val fromEnv = fromOption(
      Option(System.getenv("COURSIER_MODE")),
      "COURSIER_MODE environment variable"
    )

    def fromProps = fromOption(
      sys.props.get("coursier.mode"),
      "Java property coursier.mode"
    )

    fromEnv
      .orElse(fromProps)
      .getOrElse(noEnvCachePolicies)
  }

}
