package org.apache.spark.h2o

import java.io.File

import com.google.common.io.Files
import org.apache.spark.{SparkContext, SparkEnv}
import water.{H2OApp, H2O}

/**
 * Support methods.
 */
private[spark] object H2OContextUtils {

  /** Helper type expression a tuple of ExecutorId, IP, port */
  type NodeDesc = (String, String, Int)

  /** Generates and distributes a flatfile around Spark cluster.
    *
    * @param distRDD
    * @param basePort
    * @param incrPort
    * @return
    */
  def collectNodesInfo(distRDD: RDD[Int], basePort: Int, incrPort: Int): Array[NodeDesc] = {
    // Collect flatfile - tuple of (IP, port)
    val nodes = distRDD.map { index =>
      val env = SparkEnv.get
      ( env.executorId,
        // java.net.InetAddress.getLocalHost.getAddress.map(_ & 0xFF).mkString("."),
        // Use existing Akka setup since Spark at this point is already communicating
        env.actorSystem.settings.config.getString("akka.remote.netty.tcp.hostname"),
        // FIXME: verify that port is available
        (basePort + incrPort*index))
    }.collect()
    nodes
  }

  def saveAsFile(content: String): File = {
    val tmpDir = Files.createTempDir()
    tmpDir.deleteOnExit()
    val flatFile = new File(tmpDir, "flatfile.txt")
    val p = new java.io.PrintWriter(flatFile)
    try {
      p.print(content)
    } finally {
      p.close()
    }
    flatFile
  }

  def toFlatFileString(executors: Array[NodeDesc]):String = {
    executors.map( en => s"${en._2}:${en._3}").mkString("\n")
  }

  def toH2OArgs(h2oArgs: Array[String], h2oConf: H2OConf, executors: Array[NodeDesc]): Array[String] = {
    toH2OArgs(
      h2oArgs,
      if (h2oConf.useFlatFile)
        Some(toFlatFileString(executors))
      else
        None)
  }

  def toH2OArgs(h2oArgs: Array[String], flatFileString: Option[String]): Array[String] = {
    val launcherArgs = flatFileString
      .map(f => saveAsFile(f))
      .map(f => h2oArgs ++ Array("-flatfile", f.getAbsolutePath))
      .getOrElse(h2oArgs)
    launcherArgs
  }

  /**
   * Start H2O nodes on given executors.
   *
   * @param sc  Spark context
   * @param spreadRDD  helper RDD spread over all executors
   * @param executors list of tuples (executorId,ip,port) defining executors serving as a platform for
   *                  launching H2O services
   * @param h2oArgs arguments passed to H2O instances
   * @return return a tuple containing executorId and status of H2O node
   */
  def startH2O( sc: SparkContext,
                spreadRDD: RDD[Int],
                executors: Array[NodeDesc],
                h2oConf: H2OConf,
                h2oArgs: Array[String]): Array[(String,Boolean)] = {
    //val executorIds = executors.map(_._1)
    val flatFileString =
      if (h2oConf.useFlatFile)
        Some(toFlatFileString(executors))
       else
        None

    spreadRDD.map { index =>  // RDD partition index
      // This executor
      val executorId = SparkEnv.get.executorId
      val node = executors.find( n => executorId.equals(n._1))
      if (node.isDefined) {
        // Find the node
        try {
          // Create a flatfile if required
          val ip = node.get._2
          val port = node.get._3.toString
          val launcherArgs = toH2OArgs(
            h2oArgs ++ Array("-ip", ip, "-port", port),
            flatFileString)
          // Do not launch H2O several times
          H2O.START_TIME_MILLIS.synchronized {
            if (H2O.START_TIME_MILLIS.get() == 0) {
              H2OApp.main(launcherArgs)
            }
          }
          (executorId, true)
        } catch {
          case e: Throwable => {
            e.printStackTrace()
            println(
              s""""Cannot start H2O node because: ${e.getMessage}
                 | h2o parameters: ${h2oArgs.mkString(",")}
               """.stripMargin)
            (executorId, false)
          }
        }
      } else {
        (executorId, false)
      }
    }.collect()
  }
}
