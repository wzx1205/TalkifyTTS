package com.github.lonepheasantwarrior.talkify.book.store

import com.github.lonepheasantwarrior.talkify.book.model.Gender

/**
 * 角色册：一本书的角色 → 音色绑定表
 *
 * @param bookId EPUB 内容哈希
 * @param title 书名
 * @param characters 按 [CharacterProfile.dialogueCount] 排序的角色绑定
 */
data class CharacterBook(
    val bookId: String,
    val title: String,
    val characters: List<BookCharacterBinding>
) {
    fun voiceFor(name: String): String? =
        characters.firstOrNull { it.name == name }?.voiceId
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
