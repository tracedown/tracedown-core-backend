package dev.tracedown.common.models

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

object TotpRecoveryCodes : Table("totp_recovery_codes") {
    val id = javaUUID("id")
    val userId = javaUUID("user_id").references(Users.id)
    val codeHash = varchar("code_hash", 255)
    val used = bool("used").default(false)
    val usedAt = timestamp("used_at").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
