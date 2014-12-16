/**
 * Copyright (C) 2014 Securecom Inc
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

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.Log;
import android.webkit.MimeTypeMap;
import org.w3c.dom.smil.SMILDocument;
import org.w3c.dom.smil.SMILRegionElement;
import org.w3c.dom.smil.SMILMediaElement;
import com.securecomcode.messaging.util.SmilUtil;
import com.securecomcode.messaging.R;

import ws.com.google.android.mms.ContentType;
import ws.com.google.android.mms.pdu.PduPart;

import java.io.IOException;
import java.lang.Exception;


public class OtherSlide extends Slide {
    private final static String TAG = OtherSlide.class.getSimpleName();

    public OtherSlide(Context context, PduPart part) {
        super(context, part);
    }

    public OtherSlide(Context context, Uri uri) throws IOException, MediaTooLargeException {
        super(context, constructPartFromUri(context, uri));

    }

    @Override
    public boolean hasOther() {
        return true;
    }

    @Override
    public boolean hasImage() {
        return true;
    }

    @Override
    public Drawable getThumbnail(int maxWidth, int maxHeight) {
        return context.getResources().getDrawable(R.drawable.ic_menu_attach);
    }

    public static PduPart constructPartFromUri(Context context, Uri uri) throws IOException, MediaTooLargeException {
        PduPart part = new PduPart();

        if (getMediaSize(context, uri) > MAX_MESSAGE_SIZE)
            throw new MediaTooLargeException("File larger than size maximum.");

        String type = null;
        ContentResolver cR = context.getContentResolver();
        if (cR != null) {
            MimeTypeMap mime = MimeTypeMap.getSingleton();
            try {
                type = cR.getType(uri);
            } catch (Exception e) {
                Log.w(TAG, e);
            }

        }

        if (type == null) {
            part.setContentType(ContentType.OTHER_ANY.getBytes());
        } else {
            part.setContentType(type.getBytes());
        }

        part.setDataUri(uri);
        part.setContentId((System.currentTimeMillis() + "").getBytes());
        part.setName(("Other" + System.currentTimeMillis()).getBytes());

        return part;
    }

    @Override
    public SMILRegionElement getSmilRegion(SMILDocument document) {
        SMILRegionElement region = (SMILRegionElement) document.createElement("region");
        region.setId("Image");
        region.setLeft(0);
        region.setTop(0);
        region.setWidth(SmilUtil.ROOT_WIDTH);
        region.setHeight(SmilUtil.ROOT_HEIGHT);
        region.setFit("meet");
        return region;
    }

    @Override
    public SMILMediaElement getMediaElement(SMILDocument document) {
        return SmilUtil.createMediaElement("other", document, new String(getPart().getName()));
    }


}
