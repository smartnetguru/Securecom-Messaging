/**
 * Copyright (C) 2014 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.whispersystems.textsecure.api;

import org.whispersystems.libaxolotl.InvalidMessageException;
import org.whispersystems.textsecure.api.crypto.AttachmentCipherInputStream;
import org.whispersystems.textsecure.api.messages.TextSecureAttachmentPointer;
import org.whispersystems.textsecure.api.push.TrustStore;
import org.whispersystems.textsecure.internal.push.PushServiceSocket;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

public class TextSecureMessageReceiver {

  private final PushServiceSocket socket;

  public TextSecureMessageReceiver(String url, TrustStore trustStore,
                                   String user, String password)
  {
    this.socket = new PushServiceSocket(url, trustStore, user, password);
  }

  public InputStream retrieveAttachment(TextSecureAttachmentPointer pointer, File destination)
      throws IOException, InvalidMessageException
  {
    socket.retrieveAttachment(pointer.getRelay().orNull(), pointer.getId(), destination);
    return new AttachmentCipherInputStream(destination, pointer.getKey());
  }

}
