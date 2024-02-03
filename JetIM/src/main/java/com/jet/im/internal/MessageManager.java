package com.jet.im.internal;

import android.text.TextUtils;

import com.jet.im.JErrorCode;
import com.jet.im.JetIMConst;
import com.jet.im.internal.core.JetIMCore;
import com.jet.im.interfaces.IMessageManager;
import com.jet.im.internal.core.network.JWebSocket;
import com.jet.im.internal.core.network.QryHisMsgCallback;
import com.jet.im.internal.core.network.RecallMessageCallback;
import com.jet.im.internal.core.network.SendMessageCallback;
import com.jet.im.internal.model.ConcreteMessage;
import com.jet.im.internal.model.messages.RecallCmdMessage;
import com.jet.im.model.Conversation;
import com.jet.im.model.Message;
import com.jet.im.model.MessageContent;
import com.jet.im.model.messages.FileMessage;
import com.jet.im.model.messages.ImageMessage;
import com.jet.im.model.messages.RecallInfoMessage;
import com.jet.im.model.messages.TextMessage;
import com.jet.im.model.messages.VideoMessage;
import com.jet.im.model.messages.VoiceMessage;
import com.jet.im.utils.LoggerUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MessageManager implements IMessageManager {

    public MessageManager(JetIMCore core) {
        this.mCore = core;
        ContentTypeCenter.getInstance().registerContentType(TextMessage.class);
        ContentTypeCenter.getInstance().registerContentType(ImageMessage.class);
        ContentTypeCenter.getInstance().registerContentType(FileMessage.class);
        ContentTypeCenter.getInstance().registerContentType(VoiceMessage.class);
        ContentTypeCenter.getInstance().registerContentType(VideoMessage.class);
        ContentTypeCenter.getInstance().registerContentType(RecallInfoMessage.class);
        ContentTypeCenter.getInstance().registerContentType(RecallCmdMessage.class);
    }
    private final JetIMCore mCore;

    @Override
    public Message sendMessage(MessageContent content, Conversation conversation, ISendMessageCallback callback) {
        ConcreteMessage message = new ConcreteMessage();
        message.setContent(content);
        message.setConversation(conversation);
        message.setContentType(content.getContentType());
        message.setDirection(Message.MessageDirection.SEND);
        message.setState(Message.MessageState.SENDING);
        message.setSenderUserId(mCore.getUserId());
        message.setClientUid(createClientUid());
        message.setTimestamp(System.currentTimeMillis());

        List<ConcreteMessage> list = new ArrayList<>(1);
        list.add(message);
        mCore.getDbManager().insertMessages(list);
        SendMessageCallback messageCallback = new SendMessageCallback(message.getClientMsgNo()) {
            @Override
            public void onSuccess(long clientMsgNo, String msgId, long timestamp, long msgIndex) {
                if (mSyncProcessing) {
                    mCachedSendTime = timestamp;
                } else {
                    mCore.setMessageSendSyncTime(timestamp);
                }
                mCore.getDbManager().updateMessageAfterSend(clientMsgNo, msgId, timestamp, msgIndex);
                message.setClientMsgNo(clientMsgNo);
                message.setMessageId(msgId);
                message.setTimestamp(timestamp);
                message.setMsgIndex(msgIndex);
                message.setState(Message.MessageState.SENT);
                if (callback != null) {
                    callback.onSuccess(message);
                }
            }

            @Override
            public void onError(int errorCode, long clientMsgNo) {
                message.setState(Message.MessageState.FAIL);
                mCore.getDbManager().messageSendFail(clientMsgNo);
                if (callback != null) {
                    message.setClientMsgNo(clientMsgNo);
                    callback.onError(message, errorCode);
                }
            }
        };
        if (mCore.getWebSocket() != null) {
            mCore.getWebSocket().sendIMMessage(content, conversation, message.getClientUid(), messageCallback);
        }
        return message;
    }

    @Override
    public Message resendMessage(Message message, ISendMessageCallback callback) {
        if (message.getClientMsgNo() <= 0
        || message.getContent() == null
        || message.getConversation() == null
        || message.getConversation().getConversationId() == null) {
            if (callback != null) {
                callback.onError(message, ConstInternal.ErrorCode.INVALID_PARAM);
            }
            return message;
        }
        deleteMessageByClientMsgNo(message.getClientMsgNo());
        return sendMessage(message.getContent(), message.getConversation(), callback);
    }

    @Override
    public List<Message> getMessages(Conversation conversation, int count, long timestamp, JetIMConst.PullDirection direction) {
        return getMessages(conversation, count, timestamp, direction, new ArrayList<>());
    }

    @Override
    public List<Message> getMessages(Conversation conversation, int count, long timestamp, JetIMConst.PullDirection direction, List<String> contentTypes) {
        return mCore.getDbManager().getMessages(conversation, count, timestamp, direction, contentTypes);
    }

    @Override
    public List<Message> getMessagesByMessageIds(List<String> messageIdList) {
        return mCore.getDbManager().getMessagesByMessageIds(messageIdList);
    }

    @Override
    public List<Message> getMessagesByClientMsgNos(long[] clientMsgNos) {
        return mCore.getDbManager().getMessagesByClientMsgNos(clientMsgNos);
    }

    @Override
    public void deleteMessageByClientMsgNo(long clientMsgNo) {
        mCore.getDbManager().deleteMessageByClientMsgNo(clientMsgNo);
    }

    @Override
    public void deleteMessageByMessageId(String messageId) {
        mCore.getDbManager().deleteMessageByMessageId(messageId);
    }

    @Override
    public void clearMessages(Conversation conversation) {
        mCore.getDbManager().clearMessages(conversation);
    }

    @Override
    public void recallMessage(String messageId, IRecallMessageCallback callback) {
        List<String> idList = new ArrayList<>(1);
        idList.add(messageId);
        List<Message> messages = getMessagesByMessageIds(idList);
        if (messages.size() > 0) {
            Message m = messages.get(0);

            if (m.getContentType().equals(RecallInfoMessage.CONTENT_TYPE)) {
                if (callback != null) {
                    callback.onError(JErrorCode.MESSAGE_ALREADY_RECALLED);
                }
                return;
            }
            mCore.getWebSocket().recallMessage(messageId, m.getConversation(), m.getTimestamp(), new RecallMessageCallback(messageId) {
                @Override
                public void onSuccess(long timestamp) {
                    if (mSyncProcessing) {
                        mCachedSendTime = timestamp;
                    } else {
                        mCore.setMessageSendSyncTime(timestamp);
                    }
                    m.setContentType(RecallInfoMessage.CONTENT_TYPE);
                    RecallInfoMessage recallInfoMessage = new RecallInfoMessage();
                    m.setContent(recallInfoMessage);
                    mCore.getDbManager().updateMessageContent(recallInfoMessage, m.getContentType(), messageId);
                    if (callback != null) {
                        callback.onSuccess(m);
                    }
                }

                @Override
                public void onError(int errorCode) {
                    if (callback != null) {
                        callback.onError(errorCode);
                    }
                }
            });
        } else {
            if (callback != null) {
                callback.onError(JErrorCode.MESSAGE_NOT_EXIST);
            }
        }
    }

    @Override
    public void getRemoteMessages(Conversation conversation, int count, long startTime, JetIMConst.PullDirection direction, IGetMessagesCallback callback) {
        if (count > 100) {
            count = 100;
        }
        mCore.getWebSocket().queryHisMsg(conversation, startTime, count, direction, new QryHisMsgCallback() {
            @Override
            public void onSuccess(List<ConcreteMessage> messages, boolean isFinished) {
                //todo 排重
                //当拉回来的消息本地数据库存在时，需要把本地数据库的 clientMsgNo 赋值回 message 里
                mCore.getDbManager().insertMessages(messages);
                if (callback != null) {
                    List<Message> result = new ArrayList<>(messages);
                    callback.onSuccess(result);
                }
            }

            @Override
            public void onError(int errorCode) {
                if (callback != null) {
                    callback.onError(errorCode);
                }
            }
        });
    }

    @Override
    public void registerContentType(Class<? extends MessageContent> messageContentClass) {
        ContentTypeCenter.getInstance().registerContentType(messageContentClass);
    }

    @Override
    public void addListener(String key, IMessageListener listener) {
        if (listener == null || TextUtils.isEmpty(key)) {
            return;
        }
        if (mListenerMap == null) {
            mListenerMap = new ConcurrentHashMap<>();
        }
        mListenerMap.put(key, listener);
    }

    @Override
    public void removeListener(String key) {
        if (!TextUtils.isEmpty(key) && mListenerMap != null) {
            mListenerMap.remove(key);
        }
    }

    @Override
    public void addSyncListener(String key, IMessageSyncListener listener) {
        if (listener == null || TextUtils.isEmpty(key)) {
            return;
        }
        if (mSyncListenerMap == null) {
            mSyncListenerMap = new ConcurrentHashMap<>();
        }
        mSyncListenerMap.put(key, listener);
    }

    @Override
    public void removeSyncListener(String key) {
        if (!TextUtils.isEmpty(key) && mSyncListenerMap != null) {
            mSyncListenerMap.remove(key);
        }
    }


    void syncMessage() {
        mSyncProcessing = true;
        if (!mHasSetMessageListener) {
            mHasSetMessageListener = true;
            if (mCore.getWebSocket() != null) {
                mCore.getWebSocket().setMessageListener(new JWebSocket.IWebSocketMessageListener() {
                    @Override
                    public void onMessageReceive(ConcreteMessage message) {
                        List<ConcreteMessage> list = new ArrayList<>();
                        list.add(message);
                        handleReceiveMessages(list, false);
                    }

                    @Override
                    public void onMessageReceive(List<ConcreteMessage> messages, boolean isFinished) {
                        handleReceiveMessages(messages, true);

                        if (!isFinished) {
                            sync();
                        } else {
                            mSyncProcessing = false;
                            if (mCachedSendTime > 0) {
                                mCore.setMessageSendSyncTime(mCachedSendTime);
                                mCachedSendTime = -1;
                            }
                            if (mCachedReceiveTime > 0) {
                                mCore.setMessageReceiveTime(mCachedReceiveTime);
                                mCachedReceiveTime = -1;
                            }
                            if (mSyncListenerMap != null) {
                                for (Map.Entry<String, IMessageSyncListener> entry : mSyncListenerMap.entrySet()) {
                                    entry.getValue().onMessageSyncComplete();
                                }
                            }
                        }
                    }

                    @Override
                    public void onSyncNotify(long syncTime) {
                        LoggerUtils.d("onSyncNotify, syncTime is " + syncTime + ", receiveSyncTime is " + mCore.getMessageReceiveTime());
                        if (syncTime > mCore.getMessageReceiveTime()) {
                            mSyncProcessing = true;
                            sync();
                        }

                    }
                });
            }
        }
        sync();
    }
    
    private List<ConcreteMessage> messagesToSave(List<ConcreteMessage> messages) {
        List<ConcreteMessage> list = new ArrayList<>();
        for (ConcreteMessage message : messages) {
            if ((message.getFlags() & MessageContent.MessageFlag.IS_SAVE.getValue()) != 0) {
                list.add(message);
            }
        }
        return list;
    }

    private Message handleRecallCmdMessage(String messageId) {
        RecallInfoMessage recallInfoMessage = new RecallInfoMessage();
        mCore.getDbManager().updateMessageContent(recallInfoMessage, RecallInfoMessage.CONTENT_TYPE, messageId);
        List<String> ids = new ArrayList<>(1);
        ids.add(messageId);
        List<Message> messages = mCore.getDbManager().getMessagesByMessageIds(ids);
        if (messages.size() > 0) {
            return messages.get(0);
        }
        return null;
    }

    private void handleReceiveMessages(List<ConcreteMessage> messages, boolean isSync) {
        //todo 排重

        List<ConcreteMessage> messagesToSave = messagesToSave(messages);
        mCore.getDbManager().insertMessages(messagesToSave);

        long sendTime = 0;
        long receiveTime = 0;
        for (ConcreteMessage message : messages) {
            if (message.getDirection() == Message.MessageDirection.SEND) {
                sendTime = message.getTimestamp();
            } else if (message.getDirection() == Message.MessageDirection.RECEIVE) {
                receiveTime = message.getTimestamp();
            }

            //recall message
            if (message.getContentType().equals(RecallCmdMessage.CONTENT_TYPE)) {
                RecallCmdMessage cmd = (RecallCmdMessage) message.getContent();
                Message recallMessage = handleRecallCmdMessage(cmd.getOriginalMessageId());
                //recallMessage 为空表示被撤回的消息本地不存在，不需要回调
                if (recallMessage != null) {
                    if (mListenerMap != null) {
                        for (Map.Entry<String, IMessageListener> entry : mListenerMap.entrySet()) {
                            entry.getValue().onMessageRecall(recallMessage);
                        }
                    }
                }
                continue;
            }
            if (mListenerMap != null) {
                for (Map.Entry<String, IMessageListener> entry : mListenerMap.entrySet()) {
                    entry.getValue().onMessageReceive(message);
                }
            }
        }
        ////直发的消息，而且正在同步中，不直接更新 sync time
        if (!isSync && mSyncProcessing) {
            if (sendTime > 0) {
                mCachedSendTime = sendTime;
            }
            if (receiveTime > 0) {
                mCachedReceiveTime = receiveTime;
            }
        } else {
            if (sendTime > 0) {
                mCore.setMessageSendSyncTime(sendTime);
            }
            if (receiveTime > 0) {
                mCore.setMessageReceiveTime(receiveTime);
            }
        }
    }

    private void sync() {
        if (mCore.getWebSocket() != null) {
            mCore.getWebSocket().syncMessages(mCore.getMessageReceiveTime(), mCore.getMessageSendSyncTime(), mCore.getUserId());
        }
    }

    private String createClientUid() {
        long result = System.currentTimeMillis();
        result = result % 1000000;
        result = result * 1000 + mIncreaseId++;
        return Long.toString(result);
    }
    private int mIncreaseId = 0;
    private boolean mSyncProcessing = false;
    private long mCachedReceiveTime = -1;
    private long mCachedSendTime = -1;
    private boolean mHasSetMessageListener = false;
    private ConcurrentHashMap<String, IMessageListener> mListenerMap;
    private ConcurrentHashMap<String, IMessageSyncListener> mSyncListenerMap;
}
