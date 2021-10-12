/*
 * $Id$
 *
 * Copyright (C) 2003-2015 JNode.org
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation; either version 2.1 of the License, or
 * (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; If not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */
@file:JvmName("NTFSFile")
package org.jnode.fs.ntfs

import com.iwhys.classeditor.domain.ReplaceClass
import org.jnode.fs.FSFile
import org.jnode.fs.FSFileSlackSpace
import org.jnode.fs.FSFileStreams
import org.jnode.fs.FileSystem
import org.jnode.fs.ntfs.attribute.NTFSAttribute
import org.jnode.fs.ntfs.index.IndexEntry
import org.jnode.util.ByteBufferUtils
import java.io.IOException
import java.lang.UnsupportedOperationException
import java.nio.ByteBuffer
import java.util.HashMap
import java.util.LinkedHashSet

/**
 * @author vali
 * @author Ewout Prangsma (epr@users.sourceforge.net)
 */
@ReplaceClass("java-fs:0.1.4")
class NTFSFile : FSFile, FSFileSlackSpace, FSFileStreams {
    /**
     * The associated file record.
     */
    private var fileRecord: FileRecord? = null

    /**
     * The file system that contains this file.
     */
    private var fs: NTFSFileSystem
    private var indexEntry: IndexEntry? = null

    /**
     * Initialize this instance.
     *
     * @param fs         the file system.
     * @param indexEntry the index entry.
     */
    constructor(fs: NTFSFileSystem, indexEntry: IndexEntry?) {
        this.fs = fs
        this.indexEntry = indexEntry
    }

    /**
     * Initialize this instance.
     *
     * @param fs         the file system.
     * @param fileRecord the file record.
     */
    constructor(fs: NTFSFileSystem, fileRecord: FileRecord?) {
        this.fs = fs
        this.fileRecord = fileRecord
    }

    override fun getLength(): Long {
        val attributes =
            getFileRecord()!!.findAttributesByTypeAndName(NTFSAttribute.Types.DATA, null)
        if (!attributes.hasNext() && indexEntry != null) {
            // Fall back to the size stored in the index entry if the data attribute is not present (even possible??)
            val fileName = FileNameAttribute.Structure(
                indexEntry, IndexEntry.CONTENT_OFFSET
            )
            return fileName.realSize
        }
        return getFileRecord()!!.getAttributeTotalSize(NTFSAttribute.Types.DATA, null)
    }

    /*
     * (non-Javadoc)
     * @see org.jnode.fs.FSFile#setLength(long)
     */
    override fun setLength(length: Long) {
        // TODO Auto-generated method stub
    }

    /*
     * (non-Javadoc)
     * @see org.jnode.fs.FSFile#read(long, byte[], int, int)
     */
    // public void read(long fileOffset, byte[] dest, int off, int len)
    @Throws(IOException::class)
    override fun read(fileOffset: Long, destBuf: ByteBuffer) {
        // TODO optimize it also to use ByteBuffer at lower level
        val destBA = ByteBufferUtils.toByteArray(destBuf)
        val dest = destBA.toArray()
        getFileRecord()!!.readData(fileOffset, dest, 0, dest.size)
        destBA.refreshByteBuffer()
    }

    /*
     * (non-Javadoc)
     * @see org.jnode.fs.FSFile#write(long, byte[], int, int)
     */
    // public void write(long fileOffset, byte[] src, int off, int len) {
    override fun write(fileOffset: Long, src: ByteBuffer) {
        // TODO Auto-generated method stub
    }

    /*
     * (non-Javadoc)
     * @see org.jnode.fs.FSObject#isValid()
     */
    override fun isValid(): Boolean {
        return true
    }

    /*
     * (non-Javadoc)
     * @see org.jnode.fs.FSObject#getFileSystem()
     */
    override fun getFileSystem(): FileSystem<*> {
        return fs
    }

    /**
     * @return Returns the fileRecord.
     */
    fun getFileRecord(): FileRecord? {
        if (fileRecord == null) {
            try {
                fileRecord =
                    indexEntry!!.parentFileRecord.volume.mft.getIndexedFileRecord(indexEntry)
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        return fileRecord
    }

    /**
     * @param fileRecord The fileRecord to set.
     */
    fun setFileRecord(fileRecord: FileRecord?) {
        this.fileRecord = fileRecord
    }

    @Throws(IOException::class)
    override fun getSlackSpace(): kotlin.ByteArray? {
        val dataAttributes = getFileRecord()!!.findAttributesByTypeAndName(
            NTFSAttribute.Types.DATA, null
        )
        val attribute = if (dataAttributes.hasNext()) dataAttributes.next() else null
        if (attribute == null || attribute.isResident) {
            // If the data attribute is missing there is no slack space. If it is resident then another attribute might
            // immediately follow the data. So for now we'll ignore that case
            return ByteArray(0)
        }
        val clusterSize = (fileSystem as NTFSFileSystem).ntfsVolume.clusterSize
        var slackSpaceSize = clusterSize - (length % clusterSize).toInt()
        if (slackSpaceSize == clusterSize) {
            slackSpaceSize = 0
        }
        val slackSpace = ByteArray(slackSpaceSize)
        getFileRecord()!!.readData(
            NTFSAttribute.Types.DATA,
            null,
            length,
            slackSpace,
            0,
            slackSpace.size,
            false
        )
        return slackSpace
    }

    /**
     * Flush any cached data to the disk.
     *
     * @throws IOException
     */
    @Throws(IOException::class)
    override fun flush() {
        // TODO implement me
    }

    override fun getStreams(): Map<String, FSFile> {
        val streamNames: MutableSet<String> = LinkedHashSet()
        val dataAttributes = getFileRecord()!!.findAttributesByType(NTFSAttribute.Types.DATA)
        while (dataAttributes.hasNext()) {
            val attribute = dataAttributes.next()
            val attributeName = attribute.attributeName

            // The unnamed data attribute is the main file data, so ignore it
            if (attributeName != null) {
                streamNames.add(attributeName)
            }
        }
        val streams: MutableMap<String, FSFile> = HashMap()
        for (streamName in streamNames) {
            streams[streamName] = StreamFile(streamName)
        }
        return streams
    }

    /**
     * A file for reading data out of alternate streams.
     */
    inner class StreamFile
    /**
     * Creates a new stream file.
     *
     * @param attributeName the name of the alternate data stream.
     */(
        /**
         * The name of the alternate data stream.
         */
        val streamName: String
    ) : FSFile {
        /**
         * Gets the name of this stream.
         *
         * @return the stream name.
         */

        /**
         * Gets the associated file record.
         *
         * @return the file record.
         */
        fun getFileRecord(): FileRecord? {
            return this@NTFSFile.getFileRecord()
        }

        override fun getLength(): Long {
            return this@NTFSFile.getFileRecord()!!
                .getAttributeTotalSize(NTFSAttribute.Types.DATA, streamName)
        }

        @Throws(IOException::class)
        override fun setLength(length: Long) {
            throw UnsupportedOperationException("Not implemented yet")
        }

        @Throws(IOException::class)
        override fun read(fileOffset: Long, dest: ByteBuffer) {
            val destByteArray = ByteBufferUtils.toByteArray(dest)
            val destBuffer = destByteArray.toArray()
            if (fileOffset + destBuffer.size > length) {
                throw IOException("Attempt to read past the end of stream, offset: $fileOffset")
            }
            getFileRecord()!!.readData(
                NTFSAttribute.Types.DATA, streamName, fileOffset, destBuffer, 0,
                destBuffer.size, true
            )
            destByteArray.refreshByteBuffer()
        }

        @Throws(IOException::class)
        override fun write(fileOffset: Long, src: ByteBuffer) {
            throw UnsupportedOperationException("Not implemented yet")
        }

        @Throws(IOException::class)
        override fun flush() {
        }

        override fun isValid(): Boolean {
            return true
        }

        override fun getFileSystem(): FileSystem<*> {
            return this@NTFSFile.fileSystem
        }
    }
}