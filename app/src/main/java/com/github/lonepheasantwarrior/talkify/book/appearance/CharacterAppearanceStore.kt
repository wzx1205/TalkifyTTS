package com.github.lonepheasantwarrior.talkify.book.appearance

import com.github.lonepheasantwarrior.talkify.book.model.AgeBand

/**
 * 运行时角色面相/年龄记忆（会话内）
 *
 * **边走边记**：听书时旁白出现一次外貌/年龄就更新一次。
 * **允许改口**：主角易容/隐藏年龄时，后文真实面相直接覆盖旧值
 * （同 strength 后写赢；更高 strength 赢）。不做持久化，换书/重置即清空。
 */
object CharacterAppearanceStore {

    data class Snapshot(
        val age: AgeBand,
        val strength: Int,
        val hits: Int
    )

    private val profiles = HashMap<String, Snapshot>()

    fun observe(name: String, age: AgeBand, strength: Int = 2) {
        if (name.isBlank() || age == AgeBand.UNKNOWN) return
        if (name.length !in 2..4) return
        val prev = profiles[name]
        profiles[name] = when {
            prev == null -> Snapshot(age, strength, 1)
            // 后写覆盖：伪装场景靠这条
            strength >= prev.strength -> Snapshot(age, strength, prev.hits + 1)
            // 更弱的反向线索忽略，但正向同段累计
            age == prev.age -> Snapshot(prev.age, prev.strength, prev.hits + 1)
            else -> prev
        }
        if (profiles.size > 128) profiles.clear()
    }

    fun observeAll(cues: List<AppearanceCueExtractor.Cue>) {
        for (c in cues) observe(c.name, c.age, c.strength)
    }

    fun ageFor(name: String): AgeBand = profiles[name]?.age ?: AgeBand.UNKNOWN

    fun snapshotFor(name: String): Snapshot? = profiles[name]

    fun reset() {
        profiles.clear()
    }

    fun size(): Int = profiles.size
}
