import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

private const val PORT = 1805

object Users : IntIdTable() {
    val nick = varchar("nick", 50)
    val password = varchar("password", 50)
}

class User(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<User>(Users)

    var nick by Users.nick
    var password by Users.password
}

fun main() {
    Database.connect("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")

    transaction {
        addLogger(StdOutSqlLogger)
        SchemaUtils.create(Users)

        User.new {
            nick = "admin"
            password = "admin"
        }

        println(Users.selectAll())
    }

    val server = Server(PORT)
    server.setupServer()
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

            override fun checkAuth(username: String, password: String): AuthStatus {
                val result = transaction {
                    Users.select { Users.nick eq username }.map { it[Users.password] }
                }

                if (result.size == 0)
                    return AuthStatus.REGISTERED
                else if (password in result)
                    return AuthStatus.LOGGED
                else
                    return AuthStatus.WRONG_PASSWORD
            }
        }

        connectionsThread = thread {
            while (true) {
                val client = ClientHandler(server.accept(), clientActivityListener)
                client.run()
                clients.add(client)
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
    fun checkAuth(username: String, password: String): AuthStatus
}

enum class AuthStatus { REGISTERED, WRONG_PASSWORD, LOGGED }

class ClientHandler(
    private val client: Socket,
    private val clientActivityListener: ClientActivityListener
) {
    private lateinit var currentThread: Thread

    private val reader: Scanner = Scanner(client.getInputStream())
    private val writer: OutputStream = client.getOutputStream()
    private var authorized = false

    var running: Boolean = false
    var username = "${clientID++}"

    fun run() {
        running = true

        while (!authorized) {
            write("Введите логин")
            val username = reader.nextLine()
            write("Введите пароль")
            val password = reader.nextLine()

            when (clientActivityListener.checkAuth(username, password)) {
                AuthStatus.WRONG_PASSWORD -> {
                    write("Неверный пароль. Попробуйте снова")
                }

                AuthStatus.LOGGED -> {
                    write("Аутентификация прошла успешно!")
                    this.username = username
                    clientActivityListener.onClientConnected(username)
                    authorized = true
                }

                AuthStatus.REGISTERED -> {
                    write("Аккаунт успешно зарегистрирован!")
                    transaction {
                        User.new {
                            nick = username
                            this.password = password
                        }
                    }
                    this.username = username
                    clientActivityListener.onClientConnected(username)
                    authorized = true
                }
            }
        }

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