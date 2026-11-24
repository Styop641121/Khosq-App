package am.khosq.app.data

import org.json.JSONArray
import org.json.JSONObject

/** A poll: one question and two to six options. It travels as the (sealed) text of a message of kind "poll". */
data class Poll(val question: String, val options: List<String>)

object PollCodec {
    const val MAX_QUESTION = 120
    const val MAX_OPTION = 60
    const val MIN_OPTIONS = 2
    const val MAX_OPTIONS = 6

    /** Cleans what the person typed: trims, drops empty options, cuts too long lines. Null when it is not a valid poll. */
    fun clean(question: String, options: List<String>): Poll? {
        val q = question.trim().replace('\n', ' ').take(MAX_QUESTION)
        val o = options.map { x -> x.trim().replace('\n', ' ').take(MAX_OPTION) }.filter { x -> x.isNotEmpty() }.distinct().take(MAX_OPTIONS)
        if (q.isEmpty() || o.size < MIN_OPTIONS) return null
        return Poll(q, o)
    }

    fun encode(p: Poll): String {
        val arr = JSONArray()
        for (o in p.options) arr.put(o)
        return JSONObject().put("q", p.question).put("o", arr).toString()
    }

    /** Null when the text is not a poll (damaged, or the message could not be opened). */
    fun decode(text: String): Poll? = try {
        val j = JSONObject(text)
        val arr = j.getJSONArray("o")
        val opts = ArrayList<String>()
        for (i in 0 until arr.length()) opts.add(arr.getString(i))
        val q = j.getString("q")
        if (q.isEmpty() || opts.size < MIN_OPTIONS || opts.size > MAX_OPTIONS) null else Poll(q, opts)
    } catch (e: Exception) {
        null
    }

    /** The option each voter chose, as numbers; entries that are not a valid option number are ignored. */
    fun tally(votes: Map<String, String>, optionCount: Int): Map<String, Int> {
        val out = HashMap<String, Int>()
        for ((uid, v) in votes) {
            val i = v.toIntOrNull() ?: continue
            if (i in 0 until optionCount) out[uid] = i
        }
        return out
    }
}
