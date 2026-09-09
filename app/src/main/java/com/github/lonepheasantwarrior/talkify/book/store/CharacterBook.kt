package com.github.lonepheasantwarrior.talkify.book.store

import com.github.lonepheasantwarrior.talkify.book.model.Gender

/**
 * 角色册：一本书的角色 → 音色绑定表
 *
 * @param bookId EPUB 内容哈希
 * @param title 书名
 * @param characters 按 [CharacterProfile.dialogueCount] 排序的角色绑定
 * @param narratorVoiceId 旁白声线（独立于角色，避免角色多时被淹没）
 */
data class CharacterBook(
    val bookId: String,
    val title: String,
    val characters: List<BookCharacterBinding>,
    val narratorVoiceId: String = ""
) {
    fun voiceFor(name: String): String? =
        matchSpeaker(name)?.voiceId

    /**
     * 说话人 → 角色匹配：先精确，再前缀互配。
     *
     * 运行时抽取的说话人可能是人名截断（「陈平」）或带动作尾巴
     * （「顾璨放」←顾璨放下筷子），前缀互配到对白量最多的候选，
     * 保证同一角色全书记忆同一条声线与性别。
     */
    fun matchSpeaker(name: String): BookCharacterBinding? {
        characters.firstOrNull { it.name == name }?.let { return it }
        return characters.filter { candidate ->
            candidate.name != name &&
                (candidate.name.startsWith(name) || name.startsWith(candidate.name))
        }.maxByOrNull { it.dialogueCount }
    }
}

/**
 * 单个角色的音色绑定
 */
data class BookCharacterBinding(
    val name: String,
    val gender: Gender,
    val dialogueCount: Int,
    val sampleQuote: String,
    val voiceId: String
)
