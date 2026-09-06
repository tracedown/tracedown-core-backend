package dev.tracedown.common.models

import org.jetbrains.exposed.v1.core.Table
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [Tables.all] is a hand-written list, and a table left off it is a table
 * that can still be initialised by two threads at once. This scans the
 * compiled package for every Kotlin object extending [Table] and insists the
 * list names all of them.
 */
class TablesTest {

    @Test
    fun `every table object in the package is preloaded`() {
        val pkg = "dev.tracedown.common.models"
        val dirs = Thread.currentThread().contextClassLoader
            .getResources(pkg.replace('.', '/')).toList()
            .filter { it.protocol == "file" }
            .map { File(it.toURI()) }
        assertTrue(dirs.isNotEmpty(), "the models package must be on the classpath as a directory")

        val objects = dirs.flatMap { dir -> dir.listFiles().orEmpty().toList() }
            .filter { it.name.endsWith(".class") && !it.name.contains('$') }
            .map { Class.forName("$pkg." + it.name.removeSuffix(".class")) }
            .filter { Table::class.java.isAssignableFrom(it) && it.declaredFields.any { f -> f.name == "INSTANCE" } }
            .map { it.getDeclaredField("INSTANCE").get(null) as Table }
            .toSet()

        val listed = Tables.all.toSet()
        val missing = objects - listed
        assertTrue(missing.isEmpty(), "not in Tables.all: ${missing.map { it.tableName }}")
        assertEquals(objects.size, Tables.all.size, "Tables.all lists a table twice or one from elsewhere")
    }

    @Test
    fun `the two ends of the users-organizations cycle come first`() {
        assertEquals(listOf(Users, Organizations), Tables.all.take(2))
    }
}
