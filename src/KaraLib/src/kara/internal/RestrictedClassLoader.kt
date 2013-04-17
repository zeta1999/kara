package kara.internal

import java.net.URL
import java.net.URLClassLoader
import java.util.regex.Pattern

class RestrictedClassLoader(restrictions: List<String>, urls: Array<URL>, parent: ClassLoader) : URLClassLoader(urls, parent) {
    val restrictor: Pattern = Pattern.compile(restrictions.map { it.replace(".", "\\.").replace("*", ".*") }.makeString("|"))

    protected override fun findClass(name: String): Class<out Any?> {
        if (restrictor.matcher(name).matches()) {
            throw ClassNotFoundException("$name is in hot package")
        }
        return super<URLClassLoader>.findClass(name)
    }
}
