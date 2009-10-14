/* Copyright (c) 2001-2009, The HSQL Development Group
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


package org.hsqldb.index;

import org.hsqldb.HsqlNameManager;
import org.hsqldb.HsqlNameManager.HsqlName;
import org.hsqldb.Row;
import org.hsqldb.RowAVL;
import org.hsqldb.Session;
import org.hsqldb.TableBase;
import org.hsqldb.error.Error;
import org.hsqldb.error.ErrorCode;
import org.hsqldb.persist.PersistentStore;
import org.hsqldb.types.Type;

// fredt@users 20020221 - patch 513005 by sqlbob@users - corrections
// fredt@users 20020225 - patch 1.7.0 - changes to support cascading deletes
// tony_lai@users 20020820 - patch 595052 - better error message
// fredt@users 20021205 - patch 1.7.2 - changes to method signature
// fredt@users - patch 1.8.0 - reworked the interface and comparison methods
// fredt@users - patch 1.8.0 - improved reliability for cached indexes
// fredt@users - patch 1.9.0 - iterators and concurrency

/**
 * Implementation of an AVL tree with parent pointers in nodes. Subclasses
 * of Node implement the tree node objects for memory or disk storage. An
 * Index has a root Node that is linked with other nodes using Java Object
 * references or file pointers, depending on Node implementation.<p>
 * An Index object also holds information on table columns (in the form of int
 * indexes) that are covered by it.<p>
 *
 *  New class derived from Hypersonic SQL code and enhanced in HSQLDB. <p>
 *
 * @author Thomas Mueller (Hypersonic SQL Group)
 * @author Fred Toussi (fredt@users dot sourceforge.net)
 * @version 1.9.0
 * @since Hypersonic SQL
 */
public class IndexAVLMemory extends IndexAVL {

    /**
     * Constructor declaration
     *
     * @param name HsqlName of the index
     * @param id persistnece id
     * @param table table of the index
     * @param columns array of column indexes
     * @param descending boolean[]
     * @param nullsLast boolean[]
     * @param colTypes array of column types
     * @param unique is this a unique index
     * @param constraint does this index belonging to a constraint
     * @param forward is this an auto-index for an FK that refers to a table
     *   defined after this table
     */
    public IndexAVLMemory(HsqlName name, long id, TableBase table,
                          int[] columns, boolean[] descending,
                          boolean[] nullsLast, Type[] colTypes, boolean pk,
                          boolean unique, boolean constraint,
                          boolean forward) {
        super(name, id, table, columns, descending, nullsLast, colTypes, pk,
              unique, constraint, forward);
    }

    public void checkIndex(PersistentStore store) {

        readLock.lock();

        try {
            NodeAVL p = getAccessor(store);
            NodeAVL f = null;

            while (p != null) {
                f = p;

                checkNodes(store, p);

                p = p.nLeft;
            }

            p = f;

            while (f != null) {
                checkNodes(store, f);

                f = next(store, f);
            }
        } finally {
            readLock.unlock();
        }
    }

    private void checkNodes(PersistentStore store, NodeAVL p) {

        NodeAVL l = p.nLeft;
        NodeAVL r = p.nRight;

        if (l != null && l.getBalance(store) == -2) {
            System.out.print("broken index - deleted");
        }

        if (r != null && r.getBalance(store) == -2) {
            System.out.print("broken index -deleted");
        }

        if (l != null && !p.equals(l.getParent(store))) {
            System.out.print("broken index - no parent");
        }

        if (r != null && !p.equals(r.getParent(store))) {
            System.out.print("broken index - no parent");
        }
    }

    /**
     * Insert a node into the index
     */
    public void insert(Session session, PersistentStore store, Row row) {

        NodeAVL  n;
        NodeAVL  x;
        boolean  isleft  = true;
        int      compare = -1;
        Object[] rowData = row.rowData;

        writeLock.lock();

        try {
            n = getAccessor(store);
            x = n;

            if (n == null) {
                store.setAccessor(this, ((RowAVL) row).getNode(position));

                return;
            }

            while (true) {
                Row      currentRow  = n.row;
                Object[] currentData = currentRow.rowData;

                compare = 0;

                if (isSimpleOrder) {
                    compare = colTypes[0].compare(rowData[colIndex[0]],
                                                  currentData[colIndex[0]]);
                }

                if (compare == 0) {
                    compare = compareRowForInsertOrDelete(session, row,
                                                          currentRow);
                }

                if (compare == 0) {
                    throw Error.error(ErrorCode.X_23505);
                }

                isleft = compare < 0;
                x      = n;
                n      = isleft ? x.nLeft
                                : x.nRight;

                if (n == null) {
                    break;
                }
            }

            x = x.set(store, isleft, ((RowAVL) row).getNode(position));

            balance(store, x, isleft);
        } finally {
            writeLock.unlock();
        }
    }

    public void delete(PersistentStore store, NodeAVL x) {

        if (x == null) {
            return;
        }

        NodeAVL n;

        writeLock.lock();

        try {
            if (x.nLeft == null) {
                n = x.nRight;
            } else if (x.nRight == null) {
                n = x.nLeft;
            } else {
                NodeAVL d = x;

                x = x.nLeft;

                while (true) {
                    NodeAVL temp = x.nRight;

                    if (temp == null) {
                        break;
                    }

                    x = temp;
                }

                // x will be replaced with n later
                n = x.nLeft;

                // swap d and x
                int b = x.iBalance;

                x.iBalance = d.iBalance;
                d.iBalance = b;

                // set x.parent
                NodeAVL xp = x.nParent;
                NodeAVL dp = d.nParent;

                if (d.isRoot(store)) {
                    store.setAccessor(this, x);
                }

                x.nParent = dp;

                if (dp != null) {
                    if (dp.nRight == d) {
                        dp.nRight = x;
                    } else {
                        dp.nLeft = x;
                    }
                }

                // relink d.parent, x.left, x.right
                if (d == xp) {
                    d.nParent = x;

                    if (d.nLeft == x) {
                        x.nLeft = d;

                        NodeAVL dr = d.nRight;

                        x.nRight = dr;
                    } else {
                        x.nRight = d;

                        NodeAVL dl = d.nLeft;

                        x.nLeft = dl;
                    }
                } else {
                    d.nParent = xp;
                    xp.nRight = d;

                    NodeAVL dl = d.nLeft;
                    NodeAVL dr = d.nRight;

                    x.nLeft  = dl;
                    x.nRight = dr;
                }

                x.nRight.nParent = x;
                x.nLeft.nParent  = x;

                // set d.left, d.right
                d.nLeft = n;

                if (n != null) {
                    n.nParent = d;
                }

                d.nRight = null;
                x        = d;
            }

            boolean isleft = x.isFromLeft(store);

            x.replace(store, this, n);

            n = x.nParent;

            x.delete();

            while (n != null) {
                x = n;

                int sign = isleft ? 1
                                  : -1;

                switch (x.iBalance * sign) {

                    case -1 :
                        x.iBalance = 0;
                        break;

                    case 0 :
                        x.iBalance = sign;

                        return;

                    case 1 :
                        NodeAVL r = x.child(store, !isleft);
                        int     b = r.iBalance;

                        if (b * sign >= 0) {
                            x.replace(store, this, r);

                            NodeAVL child = r.child(store, isleft);

                            x.set(store, !isleft, child);
                            r.set(store, isleft, x);

                            if (b == 0) {
                                x.iBalance = sign;
                                r.iBalance = -sign;

                                return;
                            }

                            x.iBalance = 0;
                            r.iBalance = 0;
                            x          = r;
                        } else {
                            NodeAVL l = r.child(store, isleft);

                            x.replace(store, this, l);

                            b = l.iBalance;

                            r.set(store, isleft, l.child(store, !isleft));
                            l.set(store, !isleft, r);
                            x.set(store, !isleft, l.child(store, isleft));
                            l.set(store, isleft, x);

                            x.iBalance = (b == sign) ? -sign
                                                     : 0;
                            r.iBalance = (b == -sign) ? sign
                                                      : 0;
                            l.iBalance = 0;
                            x          = l;
                        }
                }

                isleft = x.isFromLeft(store);
                n      = x.nParent;
            }
        } finally {
            writeLock.unlock();
        }
    }

    private NodeAVL next(PersistentStore store, NodeAVL x) {

        NodeAVL r = x.nRight;

        if (r != null) {
            x = r;

            NodeAVL l = x.nLeft;

            while (l != null) {
                x = l;
                l = x.nLeft;
            }

            return x;
        }

        NodeAVL ch = x;

        x = x.nParent;

        while (x != null && ch == x.nRight) {
            ch = x;
            x  = x.nParent;
        }

        return x;
    }

    private NodeAVL last(PersistentStore store, NodeAVL x) {

        if (x == null) {
            return null;
        }

        readLock.lock();

        try {
            NodeAVL left = x.nLeft;

            if (left != null) {
                x = left;

                NodeAVL right = x.nRight;

                while (right != null) {
                    x     = right;
                    right = x.nRight;
                }

                return x;
            }

            NodeAVL ch = x;

            x = x.nParent;

            while (x != null && ch.equals(x.nLeft)) {
                ch = x;
                x  = x.nParent;
            }

            return x;
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Replace x with n
     *
     * @param x node
     * @param n node
     */
    private void replace(PersistentStore store, NodeAVL x, NodeAVL n) {

        if (x.isRoot(store)) {
            if (n != null) {
                n = n.setParent(store, null);
            }

            store.setAccessor(this, n);
        } else {
            x.getParent(store).set(store, x.isFromLeft(store), n);
        }
    }

    /**
     * Finds a match with a row from a different table
     *
     * @param rowdata array containing data for the index columns
     * @param rowColMap map of the data to columns
     * @param first true if the first matching node is required, false if any node
     * @return matching node or null
     */
    private NodeAVL findNode(Session session, PersistentStore store,
                             Object[] rowdata, int[] rowColMap,
                             int fieldCount) {

        readLock.lock();

        try {
            NodeAVL x = getAccessor(store);
            NodeAVL n;
            NodeAVL result = null;

            while (x != null) {
                int i = this.compareRowNonUnique(rowdata, rowColMap,
                                                 x.getData(store), fieldCount);

                if (i == 0) {
                    result = x;
                    n      = x.nLeft;
                } else if (i > 0) {
                    n = x.nRight;
                } else {
                    n = x.nLeft;
                }

                if (n == null) {
                    break;
                }

                x = n;
            }

            // MVCC 190
            if (session == null) {
                return result;
            }

            while (result != null) {
                Row row = result.row;

                if (compareRowNonUnique(
                        rowdata, rowColMap, row.rowData, fieldCount) != 0) {
                    result = null;

                    break;
                }

                if (session.database.txManager.canRead(session, row)) {
                    break;
                }

                result = next(store, result);
            }

            return result;
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Balances part of the tree after an alteration to the index.
     */
    private void balance(PersistentStore store, NodeAVL x, boolean isleft) {

        while (true) {
            int sign = isleft ? 1
                              : -1;

            switch (x.iBalance * sign) {

                case 1 :
                    x.iBalance = 0;

                    return;

                case 0 :
                    x.iBalance = -sign;
                    break;

                case -1 :
                    NodeAVL l = isleft ? x.nLeft
                                       : x.nRight;

                    if (l.iBalance == -sign) {
                        x.replace(store, this, l);

                        x = x.set(store, isleft, l.child(store, !isleft));

                        l.set(store, !isleft, x);

                        x.iBalance = 0;
                        l.iBalance = 0;
                    } else {
                        NodeAVL r = !isleft ? l.nLeft
                                            : l.nRight;

                        x.replace(store, this, r);

                        l = l.set(store, !isleft, r.child(store, isleft));
                        r = r.set(store, isleft, l);
                        x = x.set(store, isleft, r.child(store, !isleft));
                        r = r.set(store, !isleft, x);

                        int rb = r.iBalance;

                        x.iBalance = (rb == -sign) ? sign
                                                   : 0;
                        l.iBalance = (rb == sign) ? -sign
                                                  : 0;
                        r.iBalance = 0;
                    }

                    return;
            }

            if (x.nParent == null) {
                return;
            }

            isleft = x.nParent == null || x == x.nParent.nLeft;
            x      = x.nParent;
        }
    }
}