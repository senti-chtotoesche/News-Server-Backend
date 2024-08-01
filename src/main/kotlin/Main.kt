import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpExchange
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import com.google.gson.Gson
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SchemaUtils.create
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Table

// Структуры для хранения данных
data class NewsItem(val id: Int, val title: String, val description: String)
data class NewsRequest(val title: String, val description: String)
data class CategoryRequest(val name: String)

// Инициализация базы данных
object Categories : Table() {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 255)
    override val primaryKey = PrimaryKey(id)
}

object NewsTable : Table() {
    val id = integer("id").autoIncrement()
    val title = varchar("title", 255)
    val description = varchar("description", 255)
    val categoryId = integer("categoryid") references Categories.id
    override val primaryKey = PrimaryKey(id)
}

fun main() {
    // Инициализация базы данных(опять)
    Database.connect("jdbc:postgresql://localhost:5432/*******", driver = "org.postgresql.Driver",
            user = "*******", password = "*******")

    transaction {
        create(Categories, NewsTable)
    }

    val server = HttpServer.create(InetSocketAddress(8080), 0)
    server.executor = Executors.newFixedThreadPool(4)


    server.createContext("/", RootHandler())
    server.createContext("/categories", CategoriesHandler())
    server.createContext("/categories/", NewsHandler()) // Обработчик для новостей в категории !!!


    server.start()
    println("Server is running on http://localhost:8080/")
}

class RootHandler : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        val response = "Hello, World!"
        exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
        val os: OutputStream = exchange.responseBody
        os.write(response.toByteArray())
        os.close()
    }
}

class CategoriesHandler : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        when (exchange.requestMethod) {
            "GET" -> handleGet(exchange)
            "POST" -> handlePost(exchange)
            else -> {
                exchange.sendResponseHeaders(405, -1)
            }
        }
    }

    private fun handleGet(exchange: HttpExchange) {
        val gson = Gson()
        var response: String = ""

        transaction {
            val categories = Categories.selectAll().map {
                mapOf("id" to it[Categories.id], "name" to it[Categories.name])
            }
            response = gson.toJson(categories)
            exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
        }

        val os: OutputStream = exchange.responseBody
        os.write(response.toByteArray())
        os.close()
    }

    private fun handlePost(exchange: HttpExchange) {
        val requestBody = exchange.requestBody.bufferedReader().use { it.readText() }
        val categoryRequest = Gson().fromJson(requestBody, CategoryRequest::class.java)
        var response: String = ""

        transaction {
            val newCategoryId = Categories.insert {
                it[name] = categoryRequest.name
            } get Categories.id

            response = "Category added with ID: $newCategoryId"
        }

        exchange.sendResponseHeaders(201, response.toByteArray().size.toLong())
        val os: OutputStream = exchange.responseBody
        os.write(response.toByteArray())
        os.close()
    }
}

class NewsHandler : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        when (exchange.requestMethod) {
            "GET" -> handleGet(exchange)
            "POST" -> handlePost(exchange)
            else -> {
                exchange.sendResponseHeaders(405, -1)
            }
        }
    }

    private fun handleGet(exchange: HttpExchange) {
        val gson = Gson()
        var response: String = ""

        transaction {
            val path = exchange.requestURI.path
            println("Path: $path")
            val parts = path.split("/")
            println("Path parts: $parts")

            if (parts.size == 4 && parts[3] == "news") {
                val categoryId = parts[2].toIntOrNull()
                if (categoryId != null) {
                    val newsList = NewsTable.select { NewsTable.categoryId eq categoryId }
                            .map { NewsItem(it[NewsTable.id], it[NewsTable.title], it[NewsTable.description]) }
                    response = gson.toJson(newsList)
                    exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
                } else {
                    response = "Invalid category ID"
                    exchange.sendResponseHeaders(400, response.toByteArray().size.toLong())
                }
            } else if (parts.size == 5 && parts[3] == "news") {
                val categoryId = parts[2].toIntOrNull()
                val newsId = parts[4].toIntOrNull()
                if (categoryId != null && newsId != null) {
                    val news = NewsTable.select { NewsTable.categoryId eq categoryId and (NewsTable.id eq newsId) }.firstOrNull()
                    if (news != null) {
                        response = gson.toJson(NewsItem(news[NewsTable.id], news[NewsTable.title], news[NewsTable.description]))
                        exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
                    } else {
                        response = "News with ID: $newsId not found in category ID: $categoryId"
                        exchange.sendResponseHeaders(404, response.toByteArray().size.toLong())
                    }
                } else {
                    response = "Invalid category ID or news ID"
                    exchange.sendResponseHeaders(400, response.toByteArray().size.toLong())
                }
            } else {
                response = "Invalid request"
                exchange.sendResponseHeaders(400, response.toByteArray().size.toLong())
            }
        }

        val os: OutputStream = exchange.responseBody
        os.write(response.toByteArray())
        os.close()
    }

    private fun handlePost(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        println("Path: $path")  // Debug log
        val categoryId = path.split("/").getOrNull(2)?.toIntOrNull()
        var response: String = ""

        if (categoryId != null) {
            transaction {
                val category = Categories.select { Categories.id eq categoryId }.firstOrNull()
                if (category != null) {
                    val requestBody = exchange.requestBody.bufferedReader().use { it.readText() }
                    val newsRequest = Gson().fromJson(requestBody, NewsRequest::class.java)
                    val newNewsId = NewsTable.insert {
                        it[title] = newsRequest.title
                        it[description] = newsRequest.description
                        it[NewsTable.categoryId] = categoryId
                    } get NewsTable.id

                    response = "News added with ID: $newNewsId to category ID: $categoryId"
                    exchange.sendResponseHeaders(201, response.toByteArray().size.toLong())
                } else {
                    response = "Category with ID: $categoryId not found"
                    exchange.sendResponseHeaders(404, response.toByteArray().size.toLong())
                }
            }
        } else {
            response = "Invalid category ID"
            exchange.sendResponseHeaders(400, response.toByteArray().size.toLong())
        }

        val os: OutputStream = exchange.responseBody
        os.write(response.toByteArray())
        os.close()
    }
}
