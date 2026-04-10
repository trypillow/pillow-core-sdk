package com.pillow.mobile.audience.persistence

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema

internal object AudienceDatabaseSchema : SqlSchema<QueryResult.Value<Unit>> {
  override val version: Long = 2L

  override fun create(driver: SqlDriver): QueryResult.Value<Unit> =
    AudienceDatabase.Schema.create(driver)

  override fun migrate(
    driver: SqlDriver,
    oldVersion: Long,
    newVersion: Long,
    vararg callbacks: AfterVersion,
  ): QueryResult.Value<Unit> {
    AudienceDatabase.Schema.migrate(driver, oldVersion, newVersion, *callbacks)
    if (oldVersion < 2L) {
      migrateProfileStateToUserProperties(driver)
    }
    return QueryResult.Unit
  }

  private fun migrateProfileStateToUserProperties(driver: SqlDriver) {
    driver.execute(
      null,
      """
      CREATE TABLE profile_state_new (
        singleton_key TEXT NOT NULL PRIMARY KEY,
        external_id TEXT,
        user_properties_json TEXT,
        updated_at_epoch_ms INTEGER NOT NULL
      )
      """.trimIndent(),
      0,
    )
    driver.execute(
      null,
      """
      INSERT INTO profile_state_new (
        singleton_key,
        external_id,
        user_properties_json,
        updated_at_epoch_ms
      )
      SELECT
        singleton_key,
        external_id,
        user_traits_json,
        updated_at_epoch_ms
      FROM profile_state
      """.trimIndent(),
      0,
    )
    driver.execute(null, "DROP TABLE profile_state", 0)
    driver.execute(null, "ALTER TABLE profile_state_new RENAME TO profile_state", 0)
  }
}
