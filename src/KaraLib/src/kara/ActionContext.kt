package kara

import kotlinx.html.Link
import java.io.ByteArrayOutputStream
import java.io.InvalidClassException
import java.io.ObjectOutputStream
import java.io.Serializable
import java.lang.Exception
import java.math.BigInteger
import java.security.SecureRandom
import java.util.*
import javax.servlet.http.Cookie
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import javax.servlet.http.HttpSession
import kotlin.properties.ReadOnlyProperty
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty


fun HttpSession.getDescription() : String =
        this.attributeNames!!.toList().joinToString { "$it: ${this.getAttribute(it)}" }

fun HttpServletRequest.printAllParameters(appContext: ApplicationContext? = ActionContext.tryGet()?.appContext,
                                          prefix: String = "{", postfix: String = "}") =
    parameterMap.toList().joinToString(prefix = prefix, postfix = postfix) { (name, value) ->
        val valueStr = when {
            name in appContext?.maskedParameterNames.orEmpty() -> "*****"
            value.size == 1 -> value[0]
            else -> value.joinToString(prefix = "[", postfix = "]", separator = ",")
        }
        "$name: $valueStr"
    }

/** This contains information about the current rendering action.
 * An action context is provided by the dispatcher to the action result when it's rendered.
 */
class ActionContext(val appContext: ApplicationContext,
                    val request : HttpServletRequest,
                    val response : HttpServletResponse,
                    val params : RouteParameters,
                    val allowHttpSession: Boolean) {
    val config: ApplicationConfig = appContext.config
    val session = if (allowHttpSession) HttpActionSession(request) else NullSession

    internal val data: HashMap<Any, Any?> = HashMap()

    val startedAt : Long = System.currentTimeMillis()

    fun redirect(link: Link): ActionResult = RedirectResult(link.href())

    fun redirect(url : String) : ActionResult = RedirectResult(url.appendContext())

    private fun Serializable.toBytes(): ByteArray {
        val baos = ByteArrayOutputStream()
        ObjectOutputStream(baos).writeObject(this)
        return baos.toByteArray()
    }

    private fun ByteArray.readObject(): Any? =
            CustomClassloaderObjectInputStream(inputStream(), appContext.classLoader).readObject()

    fun toSession(key: String, value: Any?) {
        if (value !is Serializable?) error("Non serializable value to session: key=$key, value=$value")
        session.setAttribute(key, value?.toBytes())
    }

    fun fromSession(key: String): Any? {
        val raw = session.getAttribute(key)
        return when (raw) {
            is ByteArray -> try {
                raw.readObject()
            } catch (e: Exception) {
                when(e) {
                    is ClassNotFoundException, is InvalidClassException -> {
                        Application.logger.warn("Can't deserialize key $key from session. Key will be removed from session.", e)
                        session.setAttribute(key, null)
                        null
                    }
                    else -> throw e
                }
            }
            else -> raw
        }
    }

    fun flushSessionCache() {
        session.flush()
    }

    fun sessionToken(): String {
        val attr = SESSION_TOKEN_PARAMETER

        val cookie = request.cookies?.firstOrNull { it.name == attr }

        fun ActionSession.getToken() = this.getAttribute(attr) ?. let { it as String }

        return cookie?.value ?: run {
            val token = session.getToken() ?: synchronized(session.id.intern()) {
                session.getToken() ?: run {
                    val token = BigInteger(128, rnd).toString(36).take(10)
                    session.setAttribute(attr, token)
                    token
                }
            }

            if (response.getHeaders("Set-Cookie").none { it.startsWith(attr) }) {
                val newCookie = Cookie(attr, token)
                newCookie.path = "/"
                newCookie.isHttpOnly = true
                response.addCookie(newCookie)
            }
            token
        }
    }

    companion object {
        val SESSION_TOKEN_PARAMETER: String = "_st"
        private val rnd = SecureRandom()

        val contexts = ThreadLocal<ActionContext?>()

        fun current(): ActionContext = tryGet() ?: throw ContextException("Operation is not in context of an action, ActionContext not set.")

        fun tryGet(): ActionContext? = contexts.get()
    }
}

class RequestScope<T:Any> : ReadWriteProperty<Any?, T?> {
    @Suppress("UNCHECKED_CAST")
    override fun getValue(thisRef: Any?, property: KProperty<*>): T? =
            ActionContext.current().data[thisRef to property] as T?

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T?) {
        ActionContext.current().data.put(thisRef to property, value)
    }
}

class LazyRequestScope<out T:Any>(val initial: () -> T): ReadOnlyProperty<Any?, T> {
    @Suppress("UNCHECKED_CAST")
    override fun getValue(thisRef: Any?, property: KProperty<*>): T = ActionContext.current().data.getOrPut(thisRef to property, { initial() }) as T
}

class SessionScope<T:Any> : ReadWriteProperty<Any?, T?> {
    @Suppress("UNCHECKED_CAST")
    override fun getValue(thisRef: Any?, property: KProperty<*>): T? =
            ActionContext.current().fromSession(property.name) as T?

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T?) {
        ActionContext.current().toSession(property.name, value)
    }
}

class LazySessionScope<out T:Any>(private val initial: () -> T): ReadOnlyProperty<Any?, T> {
    private val store = SessionScope<T>()

    override fun getValue(thisRef: Any?, property: KProperty<*>): T =
        store.getValue(thisRef, property) ?: initial().also {
            store.setValue(thisRef, property, it)
        }
}

class ContextException(msg : String) : Exception(msg)

fun <T> ActionContext.withContext(body: () -> T): T = try {
    ActionContext.contexts.set(this)
    body()
} finally {
    ActionContext.contexts.set(null)
}

