package com.liskovsoft.mediaserviceinterfaces;

public interface ServiceManager {
    SignInService getSignInService();
    RemoteControlService getRemoteControlService();
    ContentService getContentService();
    MediaItemService getMediaItemService();
    LiveChatService getLiveChatService();
    CommentsService getCommentsService();
    NotificationsService getNotificationsService();
    ChannelGroupService getChannelGroupService();
    void invalidateCache();
    void refreshCacheIfNeeded();
    void switchNextClient();
    void switchNextClientNow();
    /** Back to the engine's default playback client, which has HLS for live streams. */
    void resetPlaybackClient();
    void switchNextSubsFormat();
    void reloadPlayerResponse(String videoId, String reloadPlaybackToken);
}
