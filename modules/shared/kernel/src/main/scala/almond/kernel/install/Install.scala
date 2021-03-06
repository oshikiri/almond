package almond.kernel.install

import java.io.{ByteArrayOutputStream, InputStream}
import java.net.URL
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}

import almond.protocol.KernelSpec
import almond.kernel.util.JupyterPath

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.util.control.NonFatal

object Install {

  private def deleteRecursively(f: Path): Unit = {
    if (Files.isDirectory(f))
      Files.list(f)
        .iterator()
        .asScala
        .foreach(deleteRecursively)

    Files.deleteIfExists(f)
  }

  private def readFully(is: => InputStream): Array[Byte] = {

    var is0: InputStream = null

    try {
      is0 = is

      val buffer = new ByteArrayOutputStream
      val data = Array.ofDim[Byte](16384)

      var nRead = 0
      while ( {
        nRead = is0.read(data, 0, data.length)
        nRead != -1
      })
        buffer.write(data, 0, nRead)

      buffer.flush()
      buffer.toByteArray
    } finally {
      if (is0 != null)
        is0.close()
    }
  }

  /**
    * Installs a Jupyter kernel.
    */
  def installIn(
    kernelId: String,
    spec: KernelSpec,
    jupyterPath: Either[Path, JupyterPath],
    logo64PngOpt: Option[URL] = None,
    force: Boolean = false,
    copyLauncher: Boolean = false
  ): Path = {

    // About the logo, don't know if other formats / sizes are allowed.

    // Ensure we can read that one upfront
    val logoContentOpt = logo64PngOpt.map(url => readFully(url.openStream()))

    val jupyterDir = jupyterPath match {
      case Left(p) => p
      case Right(j) =>
        j.paths.headOption.getOrElse(
          throw new Exception(s"No Jupyter ${j.name} directory found")
        )
    }

    val dir = jupyterDir.resolve(kernelId)

    if (Files.exists(dir)) {
      if (force) {
        deleteRecursively(dir)
        if (Files.exists(dir))
          throw new Exception(s"Could not delete $dir")
      } else
        throw new Exception(s"$dir already exists, pass --force to force erasing it")
    }

    Files.createDirectories(dir)

    if (!Files.exists(dir))
      throw new Exception(s"Could not create $dir")

    val spec0 =
      if (copyLauncher)
        launcherPos(spec.argv) match {
          case None =>
            throw new Exception(
              s"Can't copy launcher, launcher argument in command ${spec.argv.mkString(" ")} cannot be found"
            )
          case Some(pos) =>
            val launcher0 = spec.argv(pos)
            val dest = dir.resolve("launcher.jar")
            val source = Paths.get(launcher0)
            if (!Files.exists(source))
              throw new Exception(s"Launcher $launcher0 in kernel spec command not found")
            if (!Files.isRegularFile(source))
              throw new Exception(s"Launcher $launcher0 in kernel spec command is not a regular file")
            Files.copy(Paths.get(launcher0), dest)
            spec.copy(
              argv = spec.argv.updated(pos, dest.toString)
            )
        }
      else
        spec

    val kernelJsonContent = KernelSpec.encoder(spec0).spaces2.getBytes(UTF_8)

    Files.write(dir.resolve("kernel.json"), kernelJsonContent)
    for (b <- logoContentOpt)
      Files.write(dir.resolve("logo-64x64.png"), b)

    dir
  }

  /**
    * Gets the command that launched the current application if possible.
    *
    * Works if the coursier launcher is involved.
    *
    * @param filterOutArgs: arguments to filter-out
    */
  def currentAppCommand(filterOutArgs: Set[String] = Set.empty): Option[List[String]] =
    for {
      mainJar <- sys.props.get("coursier.mainJar")
      mainArgs = Iterator.from(0)
        .map(i => s"coursier.main.arg-$i")
        .map(sys.props.get)
        .takeWhile(_.nonEmpty)
        .collect { case Some(arg) if !filterOutArgs(arg) => arg }
        .toList
    } yield "java" :: "-jar" :: mainJar :: mainArgs

  /**
    * Gets the command that launched the current application if possible.
    *
    * Works if the current app is launched via a single JAR, specifying a main class in its manifest.
    *
    * @param filterOutArgs: arguments to filter-out
    */
  def fatJarCurrentAppCommand(filterOutArgs: Set[String] = Set.empty): Option[List[String]] =
    for {
      mainJar <- Option(getClass.getProtectionDomain.getCodeSource)
        .flatMap(s => Option(s.getLocation))
        .flatMap(u => scala.util.Try(Paths.get(u.toURI).toFile.getAbsolutePath).toOption)
      command <- sys.props.get("sun.java.command")
      mainArgs = command
        .split("\\s+")
        .drop(1) // drop launcher path (should be mainJar)
        .filter(!filterOutArgs(_))
        .toList
    } yield "java" :: "-jar" :: mainJar :: mainArgs

  def launcherPos(command: List[String]): Option[Int] =
    command match {
      case h :: t if h == "java" || h.endsWith("/java") =>

        @tailrec
        def helper(l: List[(String, Int)]): Option[Int] =
          l match {
            case Nil =>
              None
            case (h, _) :: t =>
              if (h == "-cp")
                None
              else if (h == "-jar")
                t.headOption.map(_._2)
              else if (h.startsWith("-"))
                helper(t)
              else
                None
          }

        helper(t.zipWithIndex)
          .map(_ + 1)
      case _ =>
        None
    }


  def install(
    defaultId: String,
    defaultDisplayName: String,
    language: String,
    options: Options,
    defaultLogoOpt: Option[URL] = None,
    connectionFileArgs: Seq[String] = Seq("--connection-file", "{connection_file}"),
    interruptMode: Option[String] = None
  ): Path = {

    val path =
      options.jupyterPath match {
        case Some(p) => Left(Paths.get(p))
        case None =>
          Right(
            if (options.global)
              JupyterPath.System
            else
              JupyterPath.User
          )
      }

    val cmd =
      options.command.map(_.trim).filter(_.nonEmpty) match {
        case Some(command) =>
          command
            .split("\\s+")
            .toSeq
        case None =>
          if (options.arg.isEmpty)
            Install.currentAppCommand(Set("--install", "--force", "--global").flatMap(s => Seq(s, s"$s=true"))).getOrElse {
              throw new Exception(
                "Could not determine the command that launches the kernel. Run the kernel with coursier, or " +
                  "pass the kernel command via -c first-arg -c second-arg …"
              )
            }
          else
            options.arg
      }

    val logoOpt =
      options.logo match {
        case None =>
          defaultLogoOpt
        case Some("") =>
          None
        case Some(f) =>
          Some(Paths.get(f).toUri.toURL)
      }

    Install.installIn(
      options.id.getOrElse(defaultId),
      KernelSpec(
        (cmd ++ connectionFileArgs).toList,
        options.displayName.getOrElse(defaultDisplayName),
        language,
        interrupt_mode = interruptMode
      ),
      path,
      logo64PngOpt = logoOpt,
      force = options.force,
      copyLauncher = options.copyLauncher0
    )
  }

  def installOrError(
    defaultId: String,
    defaultDisplayName: String,
    language: String,
    options: Options,
    defaultLogoOpt: Option[URL] = None,
    connectionFileArgs: Seq[String] = Seq("--connection-file", "{connection_file}"),
    interruptMode: Option[String] = None
  ): Either[String, Path] =
    try {
      val dir = install(
        defaultId,
        defaultDisplayName,
        language,
        options,
        defaultLogoOpt,
        connectionFileArgs,
        interruptMode
      )
      Right(dir)
    } catch {
      case NonFatal(e) =>
        Left(Option(e.getMessage).getOrElse(e.toString))
    }

}
