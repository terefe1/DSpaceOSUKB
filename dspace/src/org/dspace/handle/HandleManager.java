/*
 * HandleManager.java
 *
 * Version: $Revision$
 *
 * Date: $Date$
 *
 * Copyright (c) 2001, Hewlett-Packard Company and Massachusetts
 * Institute of Technology.  All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * - Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * - Neither the name of the Hewlett-Packard Company nor the name of the
 * Massachusetts Institute of Technology nor the names of their
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * ``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDERS OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS
 * OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR
 * TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE
 * USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH
 * DAMAGE.
 */

package org.dspace.handles;

import java.security.Security;
import java.sql.*;
import java.util.*;

import org.apache.log4j.Category;

import org.dspace.core.*;
import org.dspace.content.Item;
import org.dspace.storage.rdbms.*;

/**
 * Utility class which does handle management.
 *
 * Currently, this class simply maps handles to local facilities;
 * handles which are owned by another DSpace site are treated as
 * non-existent.
 *
 * @author  Peter Breton
 * @version $Revision$
 */
public class HandleManager
{
    /** log4j category */
    private static Category log = Category.getInstance(HandleManager.class);

    /**
     * Return the local URL for HANDLE, or null if HANDLE cannot be found.
     *
     * The returned URL is a (non-handle-based) location where a
     * dissemination of the object referred to by HANDLE can be obtained.
     *
     * @param context - DSpace context
     * @param handle - The handle
     * @return - The local URL
     * @exception SQLException - If a database error occurs
     */
    public static String resolveToURL(Context context, String handle)
        throws SQLException
    {
        TableRow dbhandle = findHandleInternal(context, handle);

        if (dbhandle == null)
            return null;

        int handletypeid = dbhandle.getIntColumn("resource_type_id");

        if (handletypeid == Constants.ITEM)
        {
            String prefix = ConfigurationManager.getProperty("handle.item.prefix");
            String url = new StringBuffer()
                .append(prefix)
                .append((prefix != null) && (!prefix.endsWith("/")) ? "/" : "")
                .append(handle)
                .toString();

            if (log.isDebugEnabled())
                log.debug("Resolved " + handle + " to " + url);

            return url;
        }

        throw new IllegalArgumentException("Only Item handles are currently supported");
    }

    /**
     * Transforms HANDLE into the canonical form hdl:HANDLE.
     *
     * No attempt is made to verify that HANDLE is in fact valid.
     *
     * @param handle - The handle
     * @return - The canonical form
     */
    public static String getCanonicalForm(String handle)
    {
        return "hdl:" + handle;
    }

    /**
     * Creates a new handle in the database.
     *
     * @param context - DSpace context
     * @param item - The item to create a handle for
     * @return  - The newly created handle
     * @exception SQLException - If a database error occurs
     */
    public static String createHandle(Context context, Item item)
        throws SQLException
    {
        TableRow handle = DatabaseManager.create(context, "Handle");
        String handleId = createId();

        handle.setColumn("handle",           handleId);
        handle.setColumn("resource_type_id", Constants.ITEM);
        handle.setColumn("resource_id",      item.getID());
        DatabaseManager.update(context, handle);

        if (log.isDebugEnabled())
            log.debug("Created new handle " + handleId);

        return handleId;
    }

    /**
     * Return the object which HANDLE maps to, or null.
     * This is the object itself, not a URL which points to it.
     *
     * @param context - DSpace context
     * @param handle - The handle to resolve
     * @return - The object which HANDLE maps to, or null if HANDLE
     * is not mapped to any object.
     * @exception SQLException - If a database error occurs
     */
    public static Object resolveToObject(Context context, String handle)
        throws SQLException
    {
        TableRow dbhandle = findHandleInternal(context, handle);

        if (dbhandle == null)
            return null;

        if ((dbhandle.isColumnNull("resource_type_id")) ||
            (dbhandle.isColumnNull("resource_id")))
            throw new IllegalStateException("No associated resource type");

        // Only ITEMs are supported for now
        int handletypeid = dbhandle.getIntColumn("resource_type_id");
        if (handletypeid == Constants.ITEM)
        {
            Item item = Item.find(context, dbhandle.getIntColumn("resource_id"));

            if (log.isDebugEnabled())
                log.debug("Resolved handle " + handle + " to item " +
                          (item == null ? -1 : item.getID()));

            return item;
        }

        throw new IllegalStateException("Only Item Handles are supported");
    }

    /**
     * Return the handle for an Object, or null if the Object has no
     * handle.
     *
     * @param context - DSpace context
     * @param obj - The object to obtain a handle for
     * @return - The handle for object, or null if the object has no handle.
     * @exception SQLException - If a database error occurs
     */
    public static String findHandle(Context context, Object obj)
        throws SQLException
    {
        if (!(obj instanceof Item))
            return null;

        Item item = (Item) obj;
        return getHandleInternal(context, Constants.ITEM, item.getID());
    }

    /**
     * Return all the handles which start with PREFIX.
     *
     * @param context - DSpace context
     * @param prefix - The handle prefix
     * @return - A list of the handles starting with PREFIX. The
     * list is guaranteed to be non-null. Each element of the list
     * is a String.
     * @exception SQLException - If a database error occurs
     */
    static List getHandlesForPrefix(Context context,
                                           String prefix)
        throws SQLException
    {
        String sql = "SELECT handle FROM handle WHERE handle LIKE " + prefix + "%";
        TableRowIterator iterator = DatabaseManager.query(context, null, sql);
        List results = new ArrayList();
        while (iterator.hasNext())
        {
            TableRow row = (TableRow) iterator.next();
            results.add(row.getStringColumn("handle"));
        }

        return results;
    }

    ////////////////////////////////////////
    // Internal methods
    ////////////////////////////////////////

    /**
     * Return the handle for an Object, or null if the Object has no
     * handle.
     *
     * @param context - DSpace context
     * @param type - The type of object
     * @param id - The id of object
     * @return - The handle for object, or null if the object has no handle.
     * @exception SQLException - If a database error occurs
     */
    private static String getHandleInternal(Context context, int type, int id)
        throws SQLException
    {
        String sql = new StringBuffer()
            .append("SELECT handle from Handle WHERE resource_type_id = ")
            .append(type)
            .append(" AND resource_id = ")
            .append(id)
            .toString();

        TableRow row = DatabaseManager.querySingle(context, null, sql);
        return row == null ? null : row.getStringColumn("handle");
    }

    /**
     * Find the row corresponding to HANDLE.
     *
     * @param context - DSpace context
     * @param handle - The handle to resolve
     * @return - The database row corresponding to the handle
     * @exception SQLException - If a database error occurs
     */
    private static TableRow findHandleInternal(Context context, String handle)
        throws SQLException
    {
        if (handle == null)
            throw new IllegalArgumentException("Handle is null");

        return DatabaseManager.findByUnique(context,
                                            "Handle",
                                            "handle",
                                            handle);
    }

    /**
     * Create a new handle id. The implementation uses the PK of
     * the RDBMS Handle table.
     *
     * @return - A new handle id
     * @exception SQLException - If a database error occurs
     */
    private static String createId()
        throws SQLException
    {
        String handlePrefix = ConfigurationManager.getProperty("handle.prefix");

        // Use Handle PKs as locally unique ids
        int id = DatabaseManager.getId("Handle");

        return new StringBuffer()
            .append(handlePrefix)
            .append(handlePrefix.endsWith("/") ? "" : "/")
            .append(id)
            .toString();
    }
}
