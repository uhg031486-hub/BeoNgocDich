package vn.huy.truyendich

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.LinkedHashMap

data class NameEntry(val source: String, val target: String)

data class Relationship(
    val a: String,
    val b: String,
    val relation: String,
    val address: String,
    val evidence: String,
    val context: String
) {
    fun id(): String = listOf(a, b, relation, address).joinToString("\u0001")
}

data class StoryMemoryUpdate(
    val entries: List<NameEntry>,
    val relations: List<Relationship>
) {
    companion object {
        fun empty() = StoryMemoryUpdate(emptyList(), emptyList())
    }
}

class StoryMemory private constructor(
    private val names: LinkedHashMap<String, String>,
    private val relations: LinkedHashMap<String, Relationship>,
    var dirty: Boolean = false
) {
    @Synchronized
    fun merge(update: StoryMemoryUpdate) {
        var changed = false
        update.entries.forEach { entry ->
            val source = normal(entry.source)
            val target = normal(entry.target)
            if (source.isNotBlank() && target.isNotBlank() && source.length <= 80 && target.length <= 120 && !names.containsKey(source)) {
                names[source] = target
                changed = true
            }
        }
        update.relations.forEach { item ->
            val relation = item.copy(
                a = canonical(item.a),
                b = canonical(item.b),
                relation = item.relation.take(240),
                address = item.address.take(240),
                evidence = item.evidence.take(500),
                context = item.context.take(160)
            )
            if (relation.a.isBlank() || relation.b.isBlank() || relation.a == relation.b || relation.relation.isBlank() || relation.evidence.isBlank()) return@forEach
            if (!relations.containsKey(relation.id())) {
                relations[relation.id()] = relation
                changed = true
            }
        }
        dirty = dirty || changed
    }

    @Synchronized
    fun mergeRemote(payload: JSONObject) {
        val entries = mutableListOf<NameEntry>()
        val relationships = mutableListOf<Relationship>()
        val rawEntries = payload.optJSONArray("entries") ?: JSONArray()
        for (index in 0 until rawEntries.length()) {
            val row = rawEntries.optJSONObject(index) ?: continue
            entries += NameEntry(row.optString("source"), row.optString("target"))
        }
        val rawRelations = payload.optJSONArray("relations") ?: JSONArray()
        for (index in 0 until rawRelations.length()) {
            val row = rawRelations.optJSONObject(index) ?: continue
            relationships += Relationship(
                row.optString("a"), row.optString("b"), row.optString("relation"),
                row.optString("address"), row.optString("evidence"), row.optString("context")
            )
        }
        val wasDirty = dirty
        merge(StoryMemoryUpdate(entries, relationships))
        dirty = wasDirty || dirty
    }

    @Synchronized
    fun markSynced() {
        dirty = false
    }

    @Synchronized
    fun referenceForPrompt(): String {
        val selectedNames = names.entries.toList().takeLast(180).map { JSONObject().put("source", it.key).put("target", it.value) }
        val selectedRelations = relations.values.toList().takeLast(60).map {
            JSONObject().put("a", it.a).put("b", it.b).put("relation", it.relation).put("address", it.address)
        }
        return JSONObject()
            .put("entries", JSONArray(selectedNames))
            .put("relations", JSONArray(selectedRelations))
            .toString()
    }

    @Synchronized
    fun namesPayload(): JSONObject = JSONObject().put("entries", JSONArray(names.entries.map {
        JSONObject().put("source", it.key).put("target", it.value)
    }))

    @Synchronized
    fun relationsPayload(): JSONObject = JSONObject().put("relations", JSONArray(relations.values.map {
        JSONObject()
            .put("a", it.a)
            .put("b", it.b)
            .put("relation", it.relation)
            .put("address", it.address)
            .put("evidence", it.evidence)
            .put("context", it.context)
    }))

    @Synchronized
    fun persistPayload(): JSONObject = JSONObject()
        .put("entries", namesPayload().getJSONArray("entries"))
        .put("relations", relationsPayload().getJSONArray("relations"))

    @Synchronized
    private fun canonical(value: String): String = names[normal(value)] ?: normal(value)

    private fun normal(value: String): String = value.trim().replace(Regex("\\s+"), " ")

    companion object {
        fun empty(): StoryMemory = StoryMemory(LinkedHashMap(), LinkedHashMap())

        fun fromJson(json: JSONObject): StoryMemory = empty().also { it.mergeRemote(json) }
    }
}

class StoryMemoryStore(private val context: Context) {
    fun load(bookId: String): StoryMemory {
        val file = fileFor(bookId)
        if (!file.exists()) return StoryMemory.empty()
        return runCatching { StoryMemory.fromJson(JSONObject(file.readText(Charsets.UTF_8))) }
            .getOrElse { StoryMemory.empty() }
    }

    fun save(bookId: String, memory: StoryMemory) {
        val target = fileFor(bookId)
        val pending = File(target.parentFile, target.name + ".pending")
        pending.writeText(memory.persistPayload().toString(), Charsets.UTF_8)
        if (target.exists()) target.delete()
        pending.renameTo(target)
    }

    private fun fileFor(bookId: String): File = File(context.filesDir, "story-memory-${bookId.take(48)}.json")
}
