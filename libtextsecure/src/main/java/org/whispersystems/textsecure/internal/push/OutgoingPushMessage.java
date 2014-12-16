/**
 * Copyright (C) 2013 Open Whisper Systems
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
package org.whispersystems.textsecure.internal.push;


import org.whispersystems.textsecure.api.push.PushAddress;
import org.whispersystems.textsecure.internal.util.Base64;

public class OutgoingPushMessage {

  private int    type;
  private int    destinationDeviceId;
  private int    destinationRegistrationId;
  private String body;

  public OutgoingPushMessage(PushAddress address, PushBody body) {
    this.type                      = body.getType();
    this.destinationDeviceId       = address.getDeviceId();
    this.destinationRegistrationId = body.getRemoteRegistrationId();
    this.body                      = Base64.encodeBytes(body.getBody());
  }

  public int getDestinationDeviceId() {
    return destinationDeviceId;
  }

  public String getBody() {
    return body;
  }

  public int getType() {
    return type;
  }

  public int getDestinationRegistrationId() {
    return destinationRegistrationId;
  }
}
