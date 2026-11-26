package am.khosq.app.data

/**
 * What the phone shows when somebody puts a reaction on one of my messages (a push of the kind "react"; see the Worker's reactPlan).
 * The push carries only the emoji, the sender's name and the group's title: never any text of a message.
 */
object ReactNote {
    /** The longest emoji a reaction may be (a family emoji is 11 UTF-16 units). */
    const val MAX_EMOJI = 16

    /**
     * The emoji out of what the push carried, or "" when it is not one: empty, too long, or holding a letter, a digit, a space or a
     * control sign. The Worker checks this too; a phone does not rely on that.
     */
    fun emojiOf(raw: String): String {
        if (raw.isEmpty() || raw.length > MAX_EMOJI) return ""
        for (ch in raw) {
            if (ch.isLetterOrDigit() || ch.isWhitespace() || ch.isISOControl()) return ""
        }
        return raw
    }

    /** "👍 Reaction to your message", and in a group "Ani: 👍 Reaction to your message". [who] is "" for a one-to-one chat. */
    fun line(emoji: String, who: String, tail: String): String {
        val name = who.trim().take(60)
        return (if (name.isEmpty()) "" else "$name: ") + emoji + " " + tail
    }

    /** The notification's own number, apart from the one the chat's messages use, so a reaction never replaces a message. */
    fun notifId(chatId: String): Int = ("react:" + chatId).hashCode()
}
