import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

private const val PORT = 1805

fun main() {
    val server = Server(PORT)
    server.setupServer()

    val scanner = Scanner(System.`in`)
    var input: String
    do {
        input = scanner.nextLine()
        println(input)
    } while (!input.contains("stop"))

    server.shutdown()
}

class Server(private val port: Int) {

    private val clients = arrayListOf<ClientHandler>()
    private lateinit var connectionsThread: Thread

    fun writeServer(message: String) {
        println("${getTime()}:$message")
    }

    fun setupServer() {
        val server = ServerSocket(port)
        writeServer("Сервер запущен на порту ${server.localPort}")

        val clientActivityListener = object : ClientActivityListener {
            override fun onClientConnected(client: String) {
                writeServer("Клиент $client подключен")

                clients.forEach {
                    it.write("connected", client)
                }
            }

            override fun onClientDisconnected(client: String) {
                writeServer("Клиент $client отключился")

                clients.forEach {
                    it.write("disconnected", client)
                }
            }

            override fun onMessageReceived(client: String, message: String) {
                writeServer("Полученный текст [$client]:$message")

                clients.forEach {
                    it.write(message, client)
                }
            }

            override fun isUsernameAlreadyTaken(newNickname: String): Boolean {
                clients.forEach {
                    if (it.running && it.username.startsWith(newNickname))
                        return true
                }
                return false
            }
        }

        connectionsThread = thread {
            while (true) {
                val client = ClientHandler(server.accept(), clientActivityListener)
                clients.add(client)
                client.run()
            }
        }

//        println("${connectionsThread.id} started")

        writeServer("Первичная настройка сервера завершена.")

    }

    fun shutdown() {
        connectionsThread.interrupt()
//        println("${connectionsThread.id} stopped")
        clients.forEach {
            if (it.running)
                it.shutdown()
        }
    }
}

interface ClientActivityListener {
    fun onClientConnected(client: String)
    fun onClientDisconnected(client: String)
    fun onMessageReceived(client: String, message: String)
    fun isUsernameAlreadyTaken(newNickname: String): Boolean
}

class ClientHandler(
    private val client: Socket,
    private val clientActivityListener: ClientActivityListener
) {
    private val reader: Scanner = Scanner(client.getInputStream())
    private val writer: OutputStream = client.getOutputStream()
    private lateinit var currentThread: Thread
    var running: Boolean = false
    var username = "${clientID++}"

    fun run() {
        running = true

        clientActivityListener.onClientConnected(username)

        currentThread = thread {
            while (running) {
                try {
                    var message = reader.nextLine()
                    if (message.equals("exit", true)) {
                        shutdown()
                        break
                    } else if (message.startsWith("/nick")) {
                        val newUsername = message.removeRange(0..message.indexOfFirst { it == ' ' })
                        if (clientActivityListener.isUsernameAlreadyTaken(newUsername)) {
                            write("Никнейм уже занят")
                            continue
                        }
                        message = "Никнейм изменён с $username на $newUsername"
                        username = newUsername
                    }

                    clientActivityListener.onMessageReceived(username, message)
                } catch (ex: Exception) {
                    shutdown()
                }
            }
        }
//        println("${currentThread.id} started")
    }

    fun write(message: String, username: String = this.username) {
        if (running)
            writer.write("${getTime()}:[$username] $message\n".toByteArray(Charset.defaultCharset()))
    }

    fun shutdown() {
        currentThread.interrupt()
//        println("${currentThread.id} stopped")
        running = false
        client.close()
        clientActivityListener.onClientDisconnected(username)
    }

}

fun getTime() = SimpleDateFormat("HH:mm:ss").format(Date())

var clientID = 0