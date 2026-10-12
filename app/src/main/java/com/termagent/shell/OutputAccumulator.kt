package com.termagent.shell

/**
 * Bounded accumulator for one command's captured output.
 *
 * A command's output is unbounded in principle (`yes`, a build log, `base64` of a file), and it is
 * handed straight to the model as a tool message: an unbounded buffer is therefore both a memory
 * problem and a token/cost problem, and one `cat` of a large file is enough to push the next
 * request past the model's context window.
 *
 * The accumulator keeps the **first** [headChars] and the **last** [tailChars] characters and drops
 * the middle, counting what it dropped. Head and tail are both kept on purpose:
 *
 *  * the head carries the start of the output — and, for the text [ShellManager] wraps this in, the
 *    `exit_code:` line the model reads first;
 *  * the tail is where a compiler, a package manager or a test runner puts its verdict, which is
 *    usually the only part that matters.
 *
 * When anything is dropped, [toString] inserts an explicit marker between the two halves, so the
 * model can tell that the output was truncated rather than silently assuming it saw everything.
 *
 * Deliberately free of Android types so the trimming rules are unit-testable on the JVM; see
 * `OutputAccumulatorTest`.
 */
internal class OutputAccumulator(
    private val headChars: Int = 0,
    private val tailChars: Int = 0,
) {

    init {
        require(headChars >= 0 && tailChars >= 0) { "limits must not be negative" }
    }

    private val head = StringBuilder()
    private val tail = StringBuilder()

    /** Characters dropped from the middle of the stream. */
    private var omitted = 0

    /** Every character accepted, including those since dropped. */
    var totalChars: Int = 0
        private set

    /** True once at least one character has been dropped. */
    val isTruncated: Boolean get() = omitted > 0

    /**
     * Feed the next slice of output.
     *
     * Append-only: the result of feeding `a` then `b` is identical to feeding `a + b` at once, which
     * is what makes this safe to drive from arbitrary pty reads.
     */
    fun append(text: String) {
        if (text.isEmpty()) return
        totalChars += text.length

        var start = 0
        if (head.length < headChars) {
            val take = minOf(headChars - head.length, text.length)
            head.append(text, 0, take)
            start = take
        }

        if (start < text.length) {
            tail.append(text, start, text.length)
            // Keep the *last* tailChars: the most recent output is the most relevant.
            if (tail.length > tailChars) {
                val drop = tail.length - tailChars
                tail.delete(0, drop)
                omitted += drop
            }
        }
    }

    /** The retained output, with an explicit marker where the middle was dropped. */
    override fun toString(): String = buildString {
        append(head)
        if (omitted > 0) {
            append("\n... [").append(omitted).append(" characters omitted] ...\n")
        }
        append(tail)
    }
}
