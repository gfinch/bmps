package bmps.core

import java.net.Socket
import java.io.{InputStreamReader, BufferedReader, OutputStream}

object WSProbe {
  def main(args: Array[String]): Unit = {
    val host = Option(System.getProperty("probe.host")).getOrElse("localhost")
    val port = Option(System.getProperty("probe.port")).flatMap(s => try Some(s.toInt) catch { case _: Throwable => None }).getOrElse(8080)
    val path = Option(System.getProperty("probe.path")).getOrElse("/ws")

    val req = new StringBuilder
    req.append(s"GET $path HTTP/1.1\r\n")
    req.append(s"Host: $host:$port\r\n")
    req.append("Upgrade: websocket\r\n")
    req.append("Connection: Upgrade\r\n")
    req.append("Sec-WebSocket-Key: x3JJHMbDL1EzLkh9GBhXDw==\r\n")
    req.append("Sec-WebSocket-Version: 13\r\n")
    req.append("User-Agent: ws-probe/1.0\r\n")
    req.append("\r\n")

    println(s"Connecting to $host:$port and sending handshake to $path")
    var socket: Socket = null
    try {
      socket = new Socket(host, port)
      socket.setSoTimeout(3000)
      val out: OutputStream = socket.getOutputStream
      out.write(req.toString.getBytes("UTF-8"))
      out.flush()

      val in = new BufferedReader(new InputStreamReader(socket.getInputStream, "UTF-8"))
      println("--- response start ---")
      var line: String = null
      try {
        line = in.readLine()
        while (line != null) {
          println(line)
          line = in.readLine()
        }
      } catch {
        case _: java.net.SocketTimeoutException => println("[read timeout]")
      }
      println("--- response end ---")
    } catch {
      case t: Throwable =>
        t.printStackTrace()
    } finally {
      if (socket != null) try socket.close() catch { case _: Throwable => () }
    }
  }
}

