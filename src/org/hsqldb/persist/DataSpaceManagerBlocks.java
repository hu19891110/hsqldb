/* Copyright (c) 2001-2011, The HSQL Development Group
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * Neither the name of the HSQL Development Group nor the names of its
 * contributors may be used to endorse or promote products derived from this
 * software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL HSQL DEVELOPMENT GROUP, HSQLDB.ORG,
 * OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */


package org.hsqldb.persist;

import org.hsqldb.error.Error;
import org.hsqldb.error.ErrorCode;
import org.hsqldb.store.BitMap;
import org.hsqldb.lib.DoubleIntIndex;
import org.hsqldb.lib.IntKeyHashMap;
import org.hsqldb.lib.Iterator;

/**
 * @author Fred Toussi (fredt@users dot sourceforge.net)
 * @version 2.3.0
 * @since 2.3.0
 */
public class DataSpaceManagerBlocks implements DataSpaceManager {

    //
    DataFileCache           cache;
    TableSpaceManagerBlocks defaultSpaceManager;
    TableSpaceManagerBlocks directorySpaceManager;

    //
    IntKeyHashMap spaceManagerList;

    //
    BlockObjectStore rootStore;
    BlockObjectStore directoryStore;
    BlockObjectStore bitMapStore;

    //
    IntArrayCachedObject rootBlock;

    //
    DirectoryBlockCachedObject firstDirectory;
    int                        spaceIdSequence = tableIdFirst;

    //
    int blockSize          = 1024 * 2;
    int bitmapIntSize      = 1024 * 2;
    int fileBlockItemCount = bitmapIntSize * 32;
    int fileBlockSize;
    int dataFileScale;

    //
    int freeItemCacheSize = 2048;

    // fragmented space size
    long totalFragmentSize;

    //
    BlockAccessor ba;

    DataSpaceManagerBlocks() {}

    public DataSpaceManagerBlocks(DataFileCache dataFileCache) {

        cache             = dataFileCache;
        dataFileScale     = cache.getDataFileScale();
        fileBlockSize     = fileBlockItemCount * dataFileScale;
        ba                = new BlockAccessor();
        spaceManagerList  = new IntKeyHashMap();
        totalFragmentSize = cache.lostSpaceSize;

        //
        directorySpaceManager = new TableSpaceManagerBlocks(this,
                tableIdDirectory, fileBlockSize, 16, dataFileScale);
        defaultSpaceManager = new TableSpaceManagerBlocks(this,
                tableIdDefault, fileBlockSize, freeItemCacheSize,
                dataFileScale);

        spaceManagerList.put(tableIdDirectory, directorySpaceManager);
        spaceManagerList.put(tableIdDefault, defaultSpaceManager);

        //
        rootStore = new BlockObjectStore(cache, directorySpaceManager,
                                         IntArrayCachedObject.class,
                                         IntArrayCachedObject.fileSizeFactor
                                         * blockSize, blockSize);
        directoryStore =
            new BlockObjectStore(cache, directorySpaceManager,
                                 DirectoryBlockCachedObject.class,
                                 DirectoryBlockCachedObject.fileSizeFactor
                                 * blockSize, blockSize);
        bitMapStore = new BlockObjectStore(cache, directorySpaceManager,
                                           BitMapCachedObject.class,
                                           BitMapCachedObject.fileSizeFactor
                                           * bitmapIntSize, bitmapIntSize);

        if (cache.spaceManagerPosition == 0) {
            initNewSpaceDirectory();

            cache.spaceManagerPosition = rootBlock.getPos() * dataFileScale;

            cache.setFileModified();
        } else {
            long pos = cache.spaceManagerPosition / dataFileScale;

            rootBlock = (IntArrayCachedObject) rootStore.get(pos, true);

            // integrity check
            if (getBlockIndexLimit() < 2) {
                throw Error.error(ErrorCode.FILE_IO_ERROR);
            }

            spaceIdSequence = getMaxSpaceId() + 1;

            initialiseTableSpace(directorySpaceManager);
            initialiseTableSpace(defaultSpaceManager);
        }

        firstDirectory = getDirectory(0, true);
    }

    private void initNewSpaceDirectory() {

        long currentSize = cache.getFileFreePos();
        long totalBlocks = (currentSize / fileBlockSize) + 1;
        long lastFreePosition = cache.enlargeFileSpace(totalBlocks
            * fileBlockSize - currentSize);

        defaultSpaceManager.initialiseFileBlock((totalBlocks - 1)
                * fileBlockSize, lastFreePosition, cache.getFileFreePos());

        long directoryBlocksSize    = calculateDirectorySpace(totalBlocks);
        int  defaultSpaceBlockCount = (int) totalBlocks;
        int directorySpaceBlockCount = (int) (directoryBlocksSize
                                              / fileBlockSize);

        lastFreePosition = cache.enlargeFileSpace(directoryBlocksSize);

        // file block is empty
        directorySpaceManager.initialiseFileBlock(lastFreePosition,
                lastFreePosition, cache.getFileFreePos());

        IntArrayCachedObject root = new IntArrayCachedObject(blockSize);

        rootStore.add(null, root, false);

        rootBlock = (IntArrayCachedObject) rootStore.get(root.getPos(), true);

        createFileBlocksInDirectory(defaultSpaceBlockCount,
                                    directorySpaceBlockCount,
                                    tableIdDirectory);
        createFileBlocksInDirectory(0, defaultSpaceBlockCount, tableIdDefault);

        int index = getBlockIndexLimit();

        // integrity check
        if ((long) index * fileBlockSize != cache.getFileFreePos()) {
            throw Error.error(ErrorCode.FILE_IO_ERROR);
        }
    }

    private long calculateDirectorySpace(long blockCount) {

        long currentSize = IntArrayCachedObject.fileSizeFactor * blockSize;    // root

        currentSize += (long) DirectoryBlockCachedObject.fileSizeFactor
                       * (blockCount + blockSize);            // directory - approx
        currentSize += (long) BitMapCachedObject.fileSizeFactor
                       * bitmapIntSize * (blockCount + 1);    // bitmaps
        currentSize = (currentSize / fileBlockSize + 1) * fileBlockSize;

        return currentSize;
    }

    /**
     * try available blocks first, then get fresh block
     */
    public long getFileBlocks(int tableId, int blockCount) {

        cache.writeLock.lock();

        try {
            int index = getExistingBlockIndex(tableId, blockCount);

            if (index > 0) {
                return index * this.fileBlockSize;
            } else {
                return getNewFileBlocks(tableId, blockCount);
            }
        } finally {
            cache.writeLock.unlock();
        }
    }

    private long getNewFileBlocks(int tableId, int blockCount) {

        cache.writeLock.lock();

        try {
            if (!directorySpaceManager.hasFileRoom(
                    BitMapCachedObject.fileSizeFactor * bitmapIntSize
                    * blockCount)) {
                long lastFreePosition = cache.enlargeFileSpace(fileBlockSize);

                directorySpaceManager.addFileBlock(lastFreePosition,
                                                   lastFreePosition,
                                                   lastFreePosition
                                                   + fileBlockSize);

                int index = getBlockIndexLimit();

                createFileBlocksInDirectory(index, 1, tableIdDirectory);

                index = getBlockIndexLimit();

                // integrity check
                if ((long) index * fileBlockSize != cache.getFileFreePos()) {
                    throw Error.error(ErrorCode.FILE_IO_ERROR);
                }

                if (tableId == tableIdDirectory) {
                    return lastFreePosition;
                }
            }

            int index = getBlockIndexLimit();

            // integrity check
            if ((long) index * fileBlockSize != cache.getFileFreePos()) {
                throw Error.error(ErrorCode.FILE_IO_ERROR);
            }

            long filePosition = cache.enlargeFileSpace(blockCount
                * fileBlockSize);

            createFileBlocksInDirectory(index, blockCount, tableId);

            // integrity check
            index = getBlockIndexLimit();

            if ((long) index * fileBlockSize != cache.getFileFreePos()) {
                throw Error.error(ErrorCode.FILE_IO_ERROR);
            }

            return filePosition;
        } finally {
            cache.writeLock.unlock();
        }
    }

    private void createFileBlocksInDirectory(int fileBlockIndex,
            int blockCount, int tableId) {

        for (int i = 0; i < blockCount; i++) {
            createFileBlocksInDirectory(fileBlockIndex + i, tableId);
        }
    }

    private void createFileBlocksInDirectory(int fileBlockIndex, int tableId) {

        DirectoryBlockCachedObject directory =
            getOrCreateDirectory(fileBlockIndex);
        int blockOffset = fileBlockIndex % blockSize;

        //
        BitMapCachedObject bitMap =
            (BitMapCachedObject) bitMapStore.getNewInstance(bitmapIntSize);

        bitMapStore.add(null, bitMap, false);

        int bitmapBlockPos = (int) (bitMap.getPos() * dataFileScale
                                    / fixedBlockSizeUnit);

        updateDirectory(directory, blockOffset, tableId, bitmapBlockPos);
    }

    private DirectoryBlockCachedObject getDirectory(int fileBlockIndex,
            boolean keep) {

        DirectoryBlockCachedObject directory;
        int                        rootIndex = fileBlockIndex / blockSize;
        long position = rootBlock.getIntArray()[rootIndex];

        if (position == 0) {
            return null;
        }

        position *= (fixedBlockSizeUnit / dataFileScale);
        directory = (DirectoryBlockCachedObject) directoryStore.get(position,
                keep);

        return directory;
    }

    private DirectoryBlockCachedObject getOrCreateDirectory(
            int fileBlockIndex) {

        DirectoryBlockCachedObject directory;
        int                        rootIndex = fileBlockIndex / blockSize;
        long position = rootBlock.getIntArray()[rootIndex];

        if (position == 0) {
            directory = createDirectory(fileBlockIndex);
        } else {
            position *= (fixedBlockSizeUnit / dataFileScale);
            directory =
                (DirectoryBlockCachedObject) directoryStore.get(position,
                    false);
        }

        return directory;
    }

    private DirectoryBlockCachedObject createDirectory(int blockIndex) {

        DirectoryBlockCachedObject directory;

        directory = new DirectoryBlockCachedObject(blockSize);

        directoryStore.add(null, directory, false);

        rootBlock.getIntArray()[blockIndex / blockSize] =
            (int) (directory.getPos() / (fixedBlockSizeUnit / dataFileScale));
        rootBlock.hasChanged = true;

        return directory;
    }

    private void updateDirectory(DirectoryBlockCachedObject directory,
                                 int offset, int tableId, int bitmapBlockPos) {

        directory =
            (DirectoryBlockCachedObject) directoryStore.get(directory.getPos(),
                true);
        directory.getTableIdArray()[offset]       = tableId;
        directory.getBitmapAddressArray()[offset] = bitmapBlockPos;
        directory.hasChanged                      = true;

        directory.keepInMemory(false);
    }

    private int getBlockIndexLimit() {

        int limit = 0;

        ba.initialise(false);

        for (;;) {
            boolean result = ba.nextBlock();

            if (!result) {
                break;
            }
        }

        limit = ba.currentBlockIndex;

        ba.reset();

        return limit;
    }

    private int getMaxSpaceId() {

        int maxId = tableIdDefault;

        ba.initialise(false);

        for (;;) {
            boolean result = ba.nextBlock();

            if (!result) {
                break;
            }

            int currentId = ba.getTableId();

            if (currentId > maxId) {
                maxId = currentId;
            }
        }

        ba.reset();

        return maxId;
    }

    private int getExistingBlockIndex(int tableId, int blockCount) {

        ba.initialise(false);

        int foundIndex = -1;
        int lastIndex  = -1;

        for (;;) {
            boolean result = ba.nextBlockForTable(tableIdEmpty);

            if (!result) {
                foundIndex = -1;

                break;
            }

            if (blockCount == 1) {
                foundIndex = ba.currentBlockIndex;

                break;
            }

            if (foundIndex == -1) {
                foundIndex = ba.currentBlockIndex;
                lastIndex  = foundIndex;

                continue;
            }

            if (ba.currentBlockIndex - foundIndex + 1 == blockCount) {
                break;
            }

            if (ba.currentBlockIndex == lastIndex + 1) {
                lastIndex = ba.currentBlockIndex;

                continue;
            }

            lastIndex  = -1;
            foundIndex = -1;
        }

        ba.reset();

        if (foundIndex > 0) {
            setDirectoryBlocksAsTable(tableId, foundIndex, blockCount);
        }

        return foundIndex;
    }

    /**
     * index and blockCount always valid
     */
    private void setDirectoryBlocksAsTable(int tableId, int index,
                                           int blockCount) {

        int                        directoryIndex = -1;
        DirectoryBlockCachedObject directory      = null;

        for (int i = index; i < index + blockCount; i++) {
            if (directoryIndex != i / blockSize) {
                if (directory != null) {
                    directory.setInMemory(false);
                }

                directory      = getDirectory(i, true);
                directoryIndex = i / blockSize;
            }

            int offset = i % blockSize;

            directory.getTableIdArray()[offset] = tableId;
        }

        directory.setInMemory(false);
    }

    public TableSpaceManager getDefaultTableSpace() {
        return defaultSpaceManager;
    }

    public TableSpaceManager getTableSpace(int spaceId) {

        if (spaceId == DataSpaceManager.tableIdDefault) {
            return defaultSpaceManager;
        }

        if (spaceId >= spaceIdSequence) {
            spaceIdSequence = spaceId + 1;
        }

        TableSpaceManagerBlocks manager =
            (TableSpaceManagerBlocks) spaceManagerList.get(spaceId);

        if (manager == null) {
            manager = new TableSpaceManagerBlocks(
                this, spaceId, fileBlockSize,
                cache.database.logger.propMaxFreeBlocks, dataFileScale);

            initialiseTableSpace(manager);
            spaceManagerList.put(spaceId, manager);
        }

        return manager;
    }

    public int getNewTableSpaceID() {

        cache.writeLock.lock();

        try {
            return spaceIdSequence++;
        } finally {
            cache.writeLock.unlock();
        }
    }

    public void freeTableSpace(int spaceId) {

        if (spaceId == tableIdDefault || spaceId == tableIdDirectory) {
            return;
        }

        cache.writeLock.lock();

        try {
            ba.initialise(true);

            for (;;) {
                boolean result = ba.nextBlockForTable(spaceId);

                if (!result) {
                    break;
                }

                cache.releaseRange(ba.currentBlockIndex * fileBlockItemCount,
                                   (ba.currentBlockIndex + 1)
                                   * fileBlockItemCount);
                ba.setTableId(tableIdEmpty);
                ba.setFreeSpaceValue(0);
                ba.setFreeBlockValue(0);

                ba.currentDir.hasChanged = true;

                ba.currentBitMap.bitMap.reset();

                ba.currentBitMap.hasChanged = true;
            }

            ba.reset();
        } finally {
            cache.writeLock.unlock();
        }
    }

    public void freeTableSpace(int spaceId, DoubleIntIndex spaceList,
                               long offset, long limit) {

        cache.writeLock.lock();

        try {
            ba.initialise(true);
            spaceList.setKeysSearchTarget();
            spaceList.sort();

            // real spaceId may be the tableIdDefault for moved spaces
            for (int i = 0; i < spaceList.size(); i++) {
                int position = spaceList.getKey(i);
                int size     = spaceList.getValue(i);
                int units    = size / dataFileScale;

                freeTableSpacePart(position, units);
            }

            long position = offset / dataFileScale;
            int  units    = (int) ((limit - offset) / dataFileScale);

            freeTableSpacePart(position, units);

            int blockIndex = (int) ((position + units) / fileBlockItemCount);

            ba.endBlockUpdate(blockIndex + 1);
            ba.reset();
        } finally {
            cache.writeLock.unlock();
        }
    }

    private void freeTableSpacePart(long position, int units) {

        for (; units > 0; ) {

            // count can cover more than one file block
            int blockIndex   = (int) (position / fileBlockItemCount);
            int offset       = (int) (position % fileBlockItemCount);
            int currentUnits = fileBlockItemCount - offset;

            if (currentUnits > units) {
                currentUnits = units;
            }

            ba.endBlockUpdate(blockIndex);
            ba.moveToBlock(blockIndex);
            ba.currentBitMap.bitMap.setRange(offset, currentUnits);

            ba.currentBitMap.hasChanged = true;
            units                       -= currentUnits;
            position                    += currentUnits;
        }
    }

    int findTableSpace(long position) {

        int blockIndex = (int) (position / fileBlockItemCount);

        ba.initialise(false);

        boolean result = ba.moveToBlock(blockIndex);

        if (!result) {
            ba.reset();

            return DataSpaceManager.tableIdDefault;
        }

        int id = ba.getTableId();

        ba.reset();

        return id;
    }

    public long getLostBlocksSize() {
        return totalFragmentSize;
    }

    public int getFileBlockSize() {
        return fileBlockSize;
    }

    public boolean isModified() {
        return true;
    }

    public void reset() {

        Iterator it = spaceManagerList.values().iterator();

        while (it.hasNext()) {
            TableSpaceManagerBlocks tableSpace =
                (TableSpaceManagerBlocks) it.next();

            tableSpace.reset();
        }
    }

    private void initialiseTableSpace(TableSpaceManagerBlocks tableSpace) {

        int spaceId    = tableSpace.getSpaceID();
        int maxFree    = 0;
        int blockIndex = -1;

        ba.initialise(false);

        for (; ba.nextBlockForTable(spaceId); ) {

            // find the largest free
            int currentFree = ba.getFreeBlockValue();

            if (currentFree > maxFree) {
                blockIndex = ba.currentBlockIndex;
                maxFree    = currentFree;
            }
        }

        ba.reset();

        if (blockIndex < 0) {
            return;
        }

        // get existing file block and initialise
        ba.initialise(true);
        ba.moveToBlock(blockIndex);

        int  freeItems = ba.getFreeBlockValue();
        long blockPos  = (long) blockIndex * fileBlockSize;

        tableSpace.initialiseFileBlock(
            blockPos, blockPos + (fileBlockSize - freeItems * dataFileScale),
            blockPos + fileBlockSize);

        int freeUnitsInBlock = ba.getFreeSpaceValue();

        freeUnitsInBlock -= freeItems;

        ba.setFreeSpaceValue((char) freeUnitsInBlock);
        ba.setFreeBlockValue(0);

        ba.currentDir.hasChanged = true;

        ba.currentBitMap.bitMap.unsetRange(fileBlockItemCount - freeItems,
                                           freeItems);

        ba.currentBitMap.hasChanged = true;

        ba.reset();
    }

    private class BlockAccessor {

        boolean                    currentKeep;
        int                        currentBlockIndex  = -1;
        int                        currentDirIndex    = -1;
        int                        currentBlockOffset = -1;
        DirectoryBlockCachedObject currentDir         = null;
        BitMapCachedObject         currentBitMap      = null;

        void initialise(boolean forUpdate) {
            currentKeep = forUpdate;
        }

        boolean nextBlock() {

            boolean result = moveToBlock(currentBlockIndex + 1);

            return result;
        }

        boolean nextBlockForTable(int tableId) {

            for (;;) {
                boolean result = moveToBlock(currentBlockIndex + 1);

                if (!result) {
                    return false;
                }

                if (getTableId() == tableId) {
                    return true;
                }
            }
        }

        boolean moveToBlock(int fileBlockIndex) {

            if (currentBlockIndex != fileBlockIndex) {
                if (currentDirIndex != fileBlockIndex / blockSize) {
                    reset();

                    currentDirIndex = fileBlockIndex / blockSize;
                    currentDir = getDirectory(currentDirIndex, currentKeep);
                }

                currentBlockIndex  = fileBlockIndex;
                currentBlockOffset = fileBlockIndex % blockSize;

                if (currentBitMap != null) {
                    currentBitMap.keepInMemory(false);

                    currentBitMap = null;
                }

                if (currentDir == null) {
                    return false;
                }

                long position =
                    currentDir.getBitmapAddressArray()[currentBlockOffset];

                if (position == 0) {
                    return false;
                }

                if (currentKeep) {
                    position *= (fixedBlockSizeUnit / dataFileScale);
                    currentBitMap =
                        (BitMapCachedObject) bitMapStore.get(position,
                            currentKeep);
                }
            }

            return true;
        }

        void reset() {

            if (currentDir != null) {
                if (currentKeep) {
                    currentDir.keepInMemory(false);
                }
            }

            if (currentBitMap != null) {
                if (currentKeep) {
                    currentBitMap.keepInMemory(false);
                }
            }

            currentBlockIndex  = -1;
            currentDirIndex    = -1;
            currentBlockOffset = -1;
            currentDir         = null;
            currentBitMap      = null;
        }

        boolean endBlockUpdate(int nextBlockIndex) {

            if (currentBlockIndex != -1
                    && currentBlockIndex != nextBlockIndex) {
                int freeUnits      = currentBitMap.bitMap.countSetBits();
                int freeBlockUnits = currentBitMap.bitMap.countSetBitsEnd();

                setFreeSpaceValue(freeUnits);
                setFreeBlockValue(freeBlockUnits);

                currentDir.hasChanged = true;

                if (freeUnits == fileBlockItemCount) {
                    setTableId(tableIdEmpty);
                    setFreeSpaceValue(0);
                    setFreeBlockValue(0);
                    currentBitMap.bitMap.reset();
                }

                return true;
            }

            return false;
        }

        int getTableId() {
            return ba.currentDir.getTableIdArray()[ba.currentBlockOffset];
        }

        void setTableId(int tableId) {
            ba.currentDir.getTableIdArray()[ba.currentBlockOffset] = tableId;
        }

        void setFreeSpaceValue(int free) {
            ba.currentDir.getFreeSpaceArray()[ba.currentBlockOffset] =
                (char) free;
        }

        char getFreeSpaceValue() {
            return ba.currentDir.getFreeSpaceArray()[ba.currentBlockOffset];
        }

        void setFreeBlockValue(int free) {
            ba.currentDir.getFreeBlockArray()[ba.currentBlockOffset] =
                (char) free;
        }

        char getFreeBlockValue() {
            return ba.currentDir.getFreeBlockArray()[ba.currentBlockOffset];
        }
    }
}
