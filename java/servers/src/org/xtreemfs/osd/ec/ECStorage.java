/*
 * Copyright (c) 2016 by Johannes Dillmann, Zuse Institute Berlin
 *
 * Licensed under the BSD License, see LICENSE file for details.
 *
 */
package org.xtreemfs.osd.ec;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.xtreemfs.common.xloc.StripingPolicyImpl;
import org.xtreemfs.foundation.buffer.BufferPool;
import org.xtreemfs.foundation.buffer.ReusableBuffer;
import org.xtreemfs.foundation.intervals.AVLTreeIntervalVector;
import org.xtreemfs.foundation.intervals.Interval;
import org.xtreemfs.foundation.intervals.IntervalVector;
import org.xtreemfs.foundation.intervals.ObjectInterval;
import org.xtreemfs.foundation.logging.Logging;
import org.xtreemfs.foundation.pbrpc.generatedinterfaces.RPC.ErrorType;
import org.xtreemfs.foundation.pbrpc.generatedinterfaces.RPC.POSIXErrno;
import org.xtreemfs.foundation.pbrpc.generatedinterfaces.RPC.RPCHeader.ErrorResponse;
import org.xtreemfs.foundation.pbrpc.utils.ErrorUtils;
import org.xtreemfs.osd.OSDRequestDispatcher;
import org.xtreemfs.osd.stages.Stage.StageRequest;
import org.xtreemfs.osd.stages.StorageStage.ECCommitVectorCallback;
import org.xtreemfs.osd.stages.StorageStage.ECGetVectorsCallback;
import org.xtreemfs.osd.storage.FileMetadata;
import org.xtreemfs.osd.storage.MetadataCache;
import org.xtreemfs.osd.storage.ObjectInformation;
import org.xtreemfs.osd.storage.ObjectInformation.ObjectStatus;
import org.xtreemfs.osd.storage.StorageLayout;

/**
 * This class contains the methods regarding EC data and IntervalVector handling. <br>
 * For sake of clarity the methods are separated to this class. <br>
 * Since the IntervalVectors are tightly coupled to the data integrity they have to be handled in the same stage to
 * reduce the chance of failures and inconsistencies.<br>
 * Unfortunately this means, that the possibly expensive IntervalVector calculations and the expensive encoding
 * operations are also run in the StorageStage. If profiling shows their impact those methods should be moved to a
 * separate stage.
 */
public class ECStorage {
    private final MetadataCache        cache;
    private final StorageLayout        layout;
    private final OSDRequestDispatcher master;
    private final boolean              checksumsEnabled;

    public ECStorage(OSDRequestDispatcher master, MetadataCache cache, StorageLayout layout, boolean checksumsEnabled) {
        this.master = master;
        this.cache = cache;
        this.layout = layout;
        this.checksumsEnabled = checksumsEnabled;
    }

    public void processGetVectors(final StageRequest rq) {
        final ECGetVectorsCallback cback = (ECGetVectorsCallback) rq.getCallback();
        final String fileId = (String) rq.getArgs()[0];

        FileMetadata fi = cache.getFileInfo(fileId);
        if (fi == null) {
            try {
                IntervalVector curVector = new AVLTreeIntervalVector();
                layout.getECIntervalVector(fileId, false, curVector);

                IntervalVector nextVector = new AVLTreeIntervalVector();
                layout.getECIntervalVector(fileId, true, nextVector);

                cback.ecGetVectorsComplete(curVector, nextVector, null);

            } catch (Exception ex) {
                cback.ecGetVectorsComplete(null, null,
                        ErrorUtils.getErrorResponse(ErrorType.ERRNO, POSIXErrno.POSIX_ERROR_EIO, ex.toString()));
            }
        } else {
            cback.ecGetVectorsComplete(fi.getECCurVector(), fi.getECNextVector(), null);
        }
    }

    public void processCommitVector(final StageRequest rq) {
        final ECCommitVectorCallback cback = (ECCommitVectorCallback) rq.getCallback();
        final String fileId = (String) rq.getArgs()[0];
        final StripingPolicyImpl sp = (StripingPolicyImpl) rq.getArgs()[1];
        final IntervalVector commitVector = (IntervalVector) rq.getArgs()[2];

        try {
            final FileMetadata fi = layout.getFileMetadata(sp, fileId);

            List<Interval> intervals = calculateIntervalsToCommit(commitVector, fi.getECNextVector());
            for (Interval interval : intervals) {
                commitECData(fileId, fi, interval);
            }

            // FIXME (jdillmann): Also delete the EC data
            AVLTreeIntervalVector emptyNextVector = new AVLTreeIntervalVector();
            layout.setECIntervalVector(fileId, emptyNextVector.serialize(), true, false);
            fi.setECNextVector(emptyNextVector);

            // FIXME (jdillmann): truncate cur?

            cback.ecCommitVectorComplete(fi.getECCurVector(), fi.getECNextVector(), null);

        } catch (IOException ex) {
            ErrorResponse error = ErrorUtils.getErrorResponse(ErrorType.ERRNO, POSIXErrno.POSIX_ERROR_EIO,
                    ex.toString(), ex);
            cback.ecCommitVectorComplete(null, null, error);
            return;
        }

    }

    List<Interval> calculateIntervalsToCommit(IntervalVector commitVector, IntervalVector nextVector) {
        Iterator<Interval> commitIt = commitVector.serialize().iterator();
        Iterator<Interval> nextIt = nextVector.serialize().iterator();

        Interval commitInterval = null;
        Interval nextInterval = null;

        long nextLastEnd = 0;

        LinkedList<Interval> intervalsToCommit = new LinkedList<Interval>();

        while ((commitInterval != null || commitIt.hasNext()) && (nextInterval != null || nextIt.hasNext())) {
            if (commitInterval == null) {
                commitInterval = commitIt.next();
            }

            if (nextInterval == null) {
                nextInterval = nextIt.next();
            }

            if (!nextInterval.isEmpty()) {
                if (commitInterval.equalsVersionId(nextInterval) && nextInterval.isOpComplete()) {
                    // commit in next interval
                    intervalsToCommit.add(nextInterval);
                }
                // replace with gap interval
                nextInterval = new ObjectInterval(commitInterval.getStart(), commitInterval.getEnd());
            }

            nextLastEnd = nextInterval.getEnd();
            if (commitInterval.getEnd() > nextInterval.getEnd()) {
                // Advance next vector
                nextInterval = null;
            } else if (commitInterval.getEnd() < nextInterval.getEnd()) {
                // Advance commit vector
                commitInterval = null;
            } else {
                // Advance both vectors
                commitInterval = null;
                nextInterval = null;
            }
        }

        // while (nextIt.hasNext()) {
        // nextInterval = nextIt.next();
        // if (!nextInterval.isEmpty()) {
        // // drop remaining intervals in next = implicit
        // }
        // nextLastEnd = nextInterval.getEnd();
        // }

        return intervalsToCommit;
    }

    void commitECData(String fileId, FileMetadata fi, Interval interval) throws IOException {
        StripingPolicyImpl sp = fi.getStripingPolicy();
        assert (interval.isOpComplete());
        long startObjNo = sp.getObjectNoForOffset(interval.getStart());
        long endObjNo = sp.getObjectNoForOffset(interval.getEnd());

        boolean consistent = true;
        try {
            Iterator<Long> objNoIt = sp.getObjectsOfOSD(sp.getRelativeOSDPosition(), startObjNo, endObjNo);
            while (objNoIt.hasNext()) {
                Long objNo = objNoIt.next();

                long startOff = Math.max(sp.getObjectStartOffset(objNo), interval.getStart());
                long endOff = Math.min(sp.getObjectEndOffset(objNo), interval.getEnd());

                String fileIdNext = fileId + ".next";
                int offset = (int) (startOff - sp.getObjectStartOffset(objNo));
                int length = (int) (endOff - startOff);
                int objVer = 1;
                // FIXME (jdillmann): Decide if/when sync should be used
                boolean sync = false;

                ReusableBuffer buf = null;
                try {
                    // TODO (jdillmann): Allow to copy data directly between files (bypass Buffer).
                    ObjectInformation obj = layout.readObject(fileIdNext, fi, objNo, offset, length, objVer);

                    if (obj.getStatus() == ObjectStatus.EXISTS) {
                        buf = obj.getData();
                    } else {
                        byte[] zeros = new byte[length];
                        buf = ReusableBuffer.wrap(zeros);
                    }

                    // FIXME (jdillmann): Handle padding objects etc.
                    layout.writeObject(fileId, fi, buf, objNo, offset, objVer, sync, false);
                    consistent = false;

                } finally {
                    if (buf != null) {
                        BufferPool.free(buf);
                    }
                }
            }

            // Finally append the interval to the cur vector and "remove" it from the next vector
            ArrayList<Interval> intervals = new ArrayList<Interval>(1);
            intervals.add(interval);
            fi.getECCurVector().insert(interval);
            layout.setECIntervalVector(fileId, intervals, false, true);

            // Remove the interval by overwriting it with an empty interval
            Interval empty = new ObjectInterval(interval.getStart(), interval.getEnd());
            intervals.set(0, empty);
            fi.getECNextVector().insert(empty);
            layout.setECIntervalVector(fileId, intervals, true, true);
            consistent = true;

        } catch (IOException ex) {
            if (!consistent) {
                // FIXME (jdillmann): Inform in detail about critical error
                Logging.logError(Logging.LEVEL_CRIT, this, ex);
            }
            throw ex;
        }

        // FIXME (jdillmann): truncate?
    }

    void abortECData(String fileId, FileMetadata fi, Interval interval) throws IOException {
        StripingPolicyImpl sp = fi.getStripingPolicy();
        assert (interval.isOpComplete());
        long startObjNo = sp.getObjectNoForOffset(interval.getStart());
        long endObjNo = sp.getObjectNoForOffset(interval.getEnd());
        int objVer = 1;

        boolean consistent = true;
        try {
            Iterator<Long> objNoIt = sp.getObjectsOfOSD(sp.getRelativeOSDPosition(), startObjNo, endObjNo);
            while (objNoIt.hasNext()) {
                Long objNo = objNoIt.next();

                // Delete the completely aborted objects
                if (interval.getStart() <= sp.getObjectStartOffset(objNo)
                        && interval.getEnd() >= sp.getObjectEndOffset(objNo)) {
                    layout.deleteObject(fileId, fi, objNo, objVer);
                    consistent = false;
                }
                // FIXME (jdillmann): Delete also, if the remaining partials are not set
                // => test on slice or iv first and iv last
                // TODO (jdillmann): Maybe overwrite partials with zeros.
            }

            // Remove the interval by overwriting it with an empty interval
            ArrayList<Interval> intervals = new ArrayList<Interval>(1);
            Interval empty = new ObjectInterval(interval.getStart(), interval.getEnd());
            intervals.add(0, empty);

            fi.getECNextVector().insert(empty);
            layout.setECIntervalVector(fileId, intervals, true, true);
            // FIXME (jdillmann): Truncate the next vector if the interval start = 0 and end >= lastEnd
            // layout.setECIntervalVector(fileId, Collections.<Interval> emptyList(), true, false);
            consistent = true;

        } catch (IOException ex) {
            if (!consistent) {
                // FIXME (jdillmann): Inform in detail about critical error
                Logging.logError(Logging.LEVEL_CRIT, this, ex);
            }
            throw ex;
        }
    }
}
