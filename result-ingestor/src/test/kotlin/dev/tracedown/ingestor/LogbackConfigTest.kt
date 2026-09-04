package dev.tracedown.ingestor

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.status.Status
import dev.tracedown.common.logging.LogContext
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import kotlin.test.assertEquals
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Loads the real service logback.xml (which `<include>`s the shared
 * logback-base.xml from tracedown-core-common) on this module's classpath and
 * asserts it parses without error and honours the org MDC — this is what
 * guards the LOG_TO_FILE-selected include, the per-org SiftingAppender, and
 * the retention config, none of which the Kotlin compiler can check.
 */
class LogbackConfigTest {

    @AfterTest
    fun restoreDefaultConfig() {
        System.clearProperty("LOG_TO_FILE")
        System.clearProperty("LOG_DIR")
        reconfigure()
        LogContext.clear()
    }

    private fun reconfigure() {
        val ctx = LoggerFactory.getILoggerFactory() as LoggerContext
        ctx.reset()
        JoranConfigurator().apply { context = ctx }
            .doConfigure(javaClass.getResourceAsStream("/logback.xml"))
    }

    private fun configErrors(): List<Status> {
        val ctx = LoggerFactory.getILoggerFactory() as LoggerContext
        return ctx.statusManager.copyOfStatusList.filter { it.level == Status.ERROR }
    }

    @Test
    fun `default config parses with no errors`() {
        System.clearProperty("LOG_TO_FILE")
        reconfigure()
        assertTrue(configErrors().isEmpty(), "logback reported: ${configErrors()}")
    }

    @Test
    fun `file logging writes a per-org file keyed on the org MDC`() {
        val dir = Files.createTempDirectory("logtest-")
        System.setProperty("LOG_TO_FILE", "true")
        System.setProperty("LOG_DIR", dir.toString())
        reconfigure()
        assertTrue(configErrors().isEmpty(), "logback reported: ${configErrors()}")

        val log = LoggerFactory.getLogger("dev.tracedown.ingestor.LogbackConfigTest")
        val org = UUID.fromString("8f3a2b00-0000-0000-0000-000000000001")
        LogContext.scoped(org = org) { log.info("tenant line") }
        log.info("system line") // no org -> default bucket

        val orgFile = dir.resolve("org/$org.log")
        val systemFile = dir.resolve("org/system.log")
        val serviceFile = dir.resolve("result-ingestor.log")
        assertTrue(Files.exists(orgFile), "expected per-org file at $orgFile")
        assertTrue(Files.exists(systemFile), "expected system-bucket file at $systemFile")
        assertTrue(Files.exists(serviceFile), "expected combined service file at $serviceFile")
        assertTrue(Files.readString(orgFile).contains("tenant line"))
    }

    @Test
    fun `console line is docker-compose style with padded service prefix and org tag`() {
        System.clearProperty("LOG_TO_FILE")
        reconfigure()
        val ctx = LoggerFactory.getILoggerFactory() as LoggerContext
        val encoder = ((ctx.getLogger(Logger.ROOT_LOGGER_NAME))
            .getAppender("STDOUT") as ConsoleAppender<*>)
            .encoder as PatternLayoutEncoder

        MDC.put(LogContext.ORG, "8f3a2b00")
        val sampleLogger = ctx.getLogger("dev.tracedown.ingestor.Sample")
        val event = LoggingEvent(
            Logger::class.java.name, sampleLogger, Level.INFO, "hello", null, null,
        )
        val line = encoder.layout.doLayout(event)
        MDC.remove(LogContext.ORG)

        // "result-ingestor" left-justified in a 24-wide field, then " | " and the org tag.
        assertTrue(line.startsWith("result-ingestor"), "prefix was: <$line>")
        assertEquals(24, line.indexOf(" | "), "service field should pad to 24: <$line>")
        assertTrue(line.contains("[org=8f3a2b00]"), "missing org tag: <$line>")
    }
}
