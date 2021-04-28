package com.symphony.bdk.core.service.message;

import com.symphony.bdk.core.auth.AuthSession;
import com.symphony.bdk.core.service.OboService;
import com.symphony.bdk.core.service.message.model.Message;
import com.symphony.bdk.core.service.pagination.model.PaginationAttribute;
import com.symphony.bdk.core.service.stream.constant.AttachmentSort;
import com.symphony.bdk.gen.api.model.MessageMetadataResponse;
import com.symphony.bdk.gen.api.model.MessageReceiptDetailResponse;
import com.symphony.bdk.gen.api.model.MessageStatus;
import com.symphony.bdk.gen.api.model.MessageSuppressionResponse;
import com.symphony.bdk.gen.api.model.StreamAttachmentItem;
import com.symphony.bdk.gen.api.model.V4ImportResponse;
import com.symphony.bdk.gen.api.model.V4ImportedMessage;
import com.symphony.bdk.gen.api.model.V4Message;
import com.symphony.bdk.gen.api.model.V4MessageBlastResponse;
import com.symphony.bdk.gen.api.model.V4Stream;
import com.symphony.bdk.template.api.TemplateEngine;

import java.time.Instant;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface MessageService extends OboMessageService, OboService<OboMessageService> {
  @Override
  OboMessageService obo(AuthSession oboSession);

  @Override
  TemplateEngine templates();

  List<V4Message> listMessages(@Nonnull V4Stream stream, @Nonnull Instant since,
      @Nonnull PaginationAttribute pagination);

  List<V4Message> listMessages(@Nonnull V4Stream stream, @Nonnull Instant since);

  List<V4Message> listMessages(@Nonnull String streamId, @Nonnull Instant since,
      @Nonnull PaginationAttribute pagination);

  List<V4Message> listMessages(@Nonnull String streamId, @Nonnull Instant since);

  @Override
  V4Message send(@Nonnull V4Stream stream, @Nonnull String message);

  @Override
  V4Message send(@Nonnull String streamId, @Nonnull String message);

  @Override
  V4Message send(@Nonnull V4Stream stream, @Nonnull Message message);

  @Override
  V4Message send(@Nonnull String streamId, @Nonnull Message message);

  V4MessageBlastResponse send(@Nonnull List<String> streamIds, @Nonnull Message message);

  byte[] getAttachment(@Nonnull String streamId, @Nonnull String messageId, @Nonnull String attachmentId);

  List<V4ImportResponse> importMessages(@Nonnull List<V4ImportedMessage> messages);

  @Override
  MessageSuppressionResponse suppressMessage(@Nonnull String messageId);

  MessageStatus getMessageStatus(@Nonnull String messageId);

  List<String> getAttachmentTypes();

  V4Message getMessage(@Nonnull String messageId);

  List<StreamAttachmentItem> listAttachments(@Nonnull String streamId, @Nullable Instant since,
      @Nullable Instant to, @Nullable Integer limit, @Nullable AttachmentSort sort);

  MessageReceiptDetailResponse listMessageReceipts(@Nonnull String messageId);

  MessageMetadataResponse getMessageRelationships(@Nonnull String messageId);
}
