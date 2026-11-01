package com.lyvt.files.core.organizer

import com.lyvt.files.core.database.OperationDao
import com.lyvt.files.core.database.OperationEntity
import com.lyvt.files.core.database.OrganizerDao
import com.lyvt.files.core.database.OrganizerRuleEntity
import com.lyvt.files.core.database.OrganizerRunEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

fun OrganizerRuleEntity.toRule() = OrganizerRule(
    name, sourceDir, destination,
    extensions.split(',').map { it.trim().trimStart('.').lowercase() }.filter { it.isNotEmpty() }.toSet(),
    namePattern, minSizeKb * 1024, maxSizeKb * 1024, olderThanDays, recursive,
)

@Singleton
class OrganizerRepository @Inject constructor(private val dao: OrganizerDao, private val ops: OperationDao) {
    val rules = dao.rules()
    val runs = dao.runs()
    suspend fun save(r: OrganizerRuleEntity) = dao.upsert(r)
    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun plan(r: OrganizerRuleEntity) = withContext(Dispatchers.IO) { OrganizerEngine.plan(r.toRule()) }

    suspend fun execute(r: OrganizerRuleEntity, plan: OrganizerPlan, onProgress: (Int) -> Unit = {}): OrganizerResult = withContext(Dispatchers.IO) {
        val res = OrganizerEngine.execute(plan, onProgress)
        dao.insertRun(OrganizerRunEntity(ruleName = r.name, timestamp = System.currentTimeMillis(), journal = OrganizerEngine.journalToJson(res.journal),
            moved = res.journal.size, failed = res.failed.size))
        dao.upsert(r.copy(lastRun = System.currentTimeMillis()))
        ops.insert(OperationEntity(type = "organize", summary = "${r.name}: ${res.journal.size} moved", success = res.failed.isEmpty()))
        res
    }

    suspend fun undo(run: OrganizerRunEntity): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val r = OrganizerEngine.undo(OrganizerEngine.journalFromJson(run.journal))
        dao.updateRun(run.copy(undone = true))
        ops.insert(OperationEntity(type = "undo", summary = "${run.ruleName}: ${r.first} restored", success = r.second == 0))
        r
    }

    suspend fun runAutomatic(): Int {
        var moved = 0
        dao.automaticRules().forEach { r -> val p = plan(r); if (p.moves.isNotEmpty()) moved += execute(r, p).journal.size }
        return moved
    }
}
