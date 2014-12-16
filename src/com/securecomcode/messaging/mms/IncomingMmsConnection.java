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
package com.securecomcode.messaging.mms;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.apache.http.HttpHost;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGetHC4;
import org.apache.http.client.methods.HttpUriRequest;

import java.io.IOException;
import java.util.Arrays;

import ws.com.google.android.mms.pdu.PduParser;
import ws.com.google.android.mms.pdu.RetrieveConf;

public class IncomingMmsConnection extends MmsConnection {
  private static final String TAG = IncomingMmsConnection.class.getSimpleName();

  public IncomingMmsConnection(Context context, Apn apn) {
    super(context, apn);
  }

  @Override
  protected HttpUriRequest constructRequest(boolean useProxy) throws IOException {
    HttpGetHC4 request = new HttpGetHC4(apn.getMmsc());
    request.addHeader("Accept", "*/*, application/vnd.wap.mms-message, application/vnd.wap.sic");
    if (useProxy) {
      HttpHost proxy = new HttpHost(apn.getProxy(), apn.getPort());
      request.setConfig(RequestConfig.custom().setProxy(proxy).build());
    }
    return request;
  }

  public static boolean isConnectionPossible(Context context, String apn) {
    try {
      getApn(context, apn);
      return true;
    } catch (ApnUnavailableException e) {
      return false;
    }
  }

  public RetrieveConf retrieve(boolean usingMmsRadio, boolean useProxyIfAvailable)
      throws IOException, ApnUnavailableException
  {
    byte[] pdu = null;

    final boolean useProxy   = useProxyIfAvailable && apn.hasProxy();
    final String  targetHost = useProxy
                             ? apn.getProxy()
                             : Uri.parse(apn.getMmsc()).getHost();
    try {
      if (checkRouteToHost(context, targetHost, usingMmsRadio)) {
        Log.w(TAG, "got successful route to host " + targetHost);
        pdu = makeRequest(useProxy);
      }
    } catch (IOException ioe) {
      Log.w(TAG, ioe);
    }

    if (pdu == null) {
      throw new IOException("Connection manager could not obtain route to host.");
    }

    RetrieveConf retrieved = (RetrieveConf)new PduParser(pdu).parse();

    if (retrieved == null) {
      Log.w(TAG, "Couldn't parse PDU, byte response: " + Arrays.toString(pdu));
      Log.w(TAG, "Couldn't parse PDU, ASCII:         " + new String(pdu));
      throw new IOException("Bad retrieved PDU");
    }

    return retrieved;
  }
}
