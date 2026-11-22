package com.liskovsoft.youtubeapi.comments.impl

import com.liskovsoft.mediaserviceinterfaces.data.CommentGroup
import com.liskovsoft.mediaserviceinterfaces.data.CommentItem
import com.liskovsoft.youtubeapi.comments.gen.CommentsResult
import com.liskovsoft.youtubeapi.next.v2.gen.getContinuationToken

internal data class CommentGroupImpl(val commentsResult: CommentsResult): CommentGroup {
    /**
     * A top-level page and a comment's replies use different continuation objects
     * (`itemSectionContinuation` and `commentThreadContinuation`), so take whichever one came back.
     */
    private val contentsAndContinuation by lazy {
        commentsResult.continuationContents?.let { contents ->
            contents.itemSectionContinuation?.let { it.contents to it.continuations }
                ?: contents.commentThreadContinuation?.let { it.contents to it.continuations }
                ?: contents.commentRepliesContinuation?.let { it.contents to it.continuations }
        }
    }

    private val commentItemWrappers by lazy {
        contentsAndContinuation?.first
    }

    private val nextCommentsKeyItem by lazy {
        contentsAndContinuation?.second?.getContinuationToken()
    }

    private val commentItems by lazy {
        commentItemWrappers?.mapNotNull { it?.let { CommentItemImpl(it) } }
    }

    override fun getComments(): List<CommentItem?>? = commentItems

    override fun getNextCommentsKey(): String? = nextCommentsKeyItem
}
