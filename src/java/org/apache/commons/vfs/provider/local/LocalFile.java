/*
 * Copyright (C) The Apache Software Foundation. All rights reserved.
 *
 * This software is published under the terms of the Apache Software License
 * version 1.1, a copy of which has been included with this distribution in
 * the LICENSE.txt file.
 */
package org.apache.commons.vfs.provider.local;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FilePermission;
import java.security.AccessController;
import org.apache.commons.vfs.FileName;
import org.apache.commons.vfs.FileObject;
import org.apache.commons.vfs.FileSelector;
import org.apache.commons.vfs.FileSystemException;
import org.apache.commons.vfs.FileType;
import org.apache.commons.vfs.provider.AbstractFileObject;
import org.apache.avalon.excalibur.i18n.ResourceManager;
import org.apache.avalon.excalibur.i18n.Resources;

/**
 * A file object implementation which uses direct file access.
 *
 * @author <a href="mailto:adammurdoch@apache.org">Adam Murdoch</a>
 * @version $Revision: 1.6 $ $Date: 2002/04/07 02:27:57 $
 */
final class LocalFile
    extends AbstractFileObject
    implements FileObject
{
    private static final Resources REZ =
        ResourceManager.getPackageResources( LocalFile.class );

    private File m_file;
    private final String m_fileName;
    private FilePermission m_requiredPerm;

    /**
     * Creates a non-root file.
     */
    public LocalFile( final LocalFileSystem fileSystem,
                      final String fileName,
                      final FileName name )
    {
        super( name, fileSystem );
        m_fileName = fileName;
    }

    /**
     * Attaches this file object to its file resource.
     */
    protected void doAttach()
        throws Exception
    {
        if( m_file == null )
        {
            m_file = new File( m_fileName );
            m_requiredPerm = new FilePermission( m_file.getAbsolutePath(), "read" );
        }
    }

    /**
     * Returns the file's type.
     */
    protected FileType doGetType()
        throws Exception
    {
        if( !m_file.exists() )
        {
            return null;
        }
        if( m_file.isDirectory() )
        {
            return FileType.FOLDER;
        }
        if( m_file.isFile() )
        {
            return FileType.FILE;
        }

        final String message = REZ.getString( "get-type.error", m_file );
        throw new FileSystemException( message );
    }

    /**
     * Returns the children of the file.
     */
    protected String[] doListChildren()
        throws Exception
    {
        return m_file.list();
    }

    /**
     * Deletes this file, and all children.
     */
    protected void doDelete()
        throws Exception
    {
        if( !m_file.delete() )
        {
            final String message = REZ.getString( "delete-file.error", m_file );
            throw new FileSystemException( message );
        }
    }

    /**
     * Creates this folder.
     */
    protected void doCreateFolder()
        throws Exception
    {
        if( !m_file.mkdir() )
        {
            final String message = REZ.getString( "create-folder.error", m_file );
            throw new FileSystemException( message );
        }
    }

    /**
     * Creates an input stream to read the content from.
     */
    protected InputStream doGetInputStream()
        throws Exception
    {
        return new FileInputStream( m_file );
    }

    /**
     * Creates an output stream to write the file content to.
     */
    protected OutputStream doGetOutputStream()
        throws Exception
    {
        return new FileOutputStream( m_file );
    }

    /**
     * Returns the size of the file content (in bytes).
     */
    protected long doGetContentSize()
        throws Exception
    {
        return m_file.length();
    }

    /**
     * Creates a temporary local copy of this file, and its descendents.
     */
    protected File doReplicateFile( final FileSelector selector )
        throws FileSystemException
    {
        final SecurityManager sm = System.getSecurityManager();
        if( sm != null )
        {
            sm.checkPermission( m_requiredPerm );
        }
        return m_file;
    }
}
