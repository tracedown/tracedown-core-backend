package dev.tracedown.common.models

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

object CaRoot : Table("ca_root") {
    val id = short("id").autoIncrement()
    val certificatePem = text("certificate_pem")
    val privateKeyEncrypted = text("private_key_encrypted")
    val privateKeyIv = varchar("private_key_iv", 64)
    val expiresAt = timestamp("expires_at")
    val createdAt = timestamp("created_at")
    val rotatedAt = timestamp("rotated_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
