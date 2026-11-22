package com.liskovsoft.youtubeapi.comments.gen

import com.liskovsoft.youtubeapi.next.v2.gen.ContinuationItem

internal data class CommentsResult(
    val continuationContents: ContinuationContents?
) {
    data class ContinuationContents(
        val itemSectionContinuation: ItemSectionContinuation?,
        /** Opens a comment's replies, from its `nestedCommentsKey` continuation. */
        val commentThreadContinuation: CommentListContinuation?,
        /** Some reply continuations come back in this shape instead. */
        val commentRepliesContinuation: CommentListContinuation?
    ) {
        data class ItemSectionContinuation(
            val contents: List<CommentItemWrapper?>?,
            val continuations: List<ContinuationItem?>?
        )
    }

    data class CommentListContinuation(
        val contents: List<CommentItemWrapper?>?,
        val continuations: List<ContinuationItem?>?
    )
}
