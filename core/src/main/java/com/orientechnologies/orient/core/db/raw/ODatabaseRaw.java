/*
 * Copyright 2010-2012 Luca Garulli (l.garulli--at--orientechnologies.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.orientechnologies.orient.core.db.raw;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Callable;

import com.orientechnologies.common.exception.OException;
import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.orient.core.Orient;
import com.orientechnologies.orient.core.cache.OLevel1RecordCache;
import com.orientechnologies.orient.core.cache.OLevel2RecordCache;
import com.orientechnologies.orient.core.db.ODatabase;
import com.orientechnologies.orient.core.db.ODatabaseLifecycleListener;
import com.orientechnologies.orient.core.db.ODatabaseListener;
import com.orientechnologies.orient.core.db.ODatabaseRecordThreadLocal;
import com.orientechnologies.orient.core.db.graph.OGraphDatabase;
import com.orientechnologies.orient.core.db.record.ODatabaseRecord;
import com.orientechnologies.orient.core.db.record.ODatabaseRecordTx;
import com.orientechnologies.orient.core.exception.ODatabaseException;
import com.orientechnologies.orient.core.exception.ORecordNotFoundException;
import com.orientechnologies.orient.core.fetch.OFetchHelper;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.intent.OIntent;
import com.orientechnologies.orient.core.storage.ORawBuffer;
import com.orientechnologies.orient.core.storage.ORecordCallback;
import com.orientechnologies.orient.core.storage.OStorage;
import com.orientechnologies.orient.core.storage.OStorage.CLUSTER_TYPE;
import com.orientechnologies.orient.core.storage.impl.local.OStorageLocal;

/**
 * Lower level ODatabase implementation. It's extended or wrapped by all the others.
 * 
 * @author Luca Garulli (l.garulli--at--orientechnologies.com)
 * 
 */
@SuppressWarnings("unchecked")
public class ODatabaseRaw implements ODatabase {
  protected String                      url;
  protected OStorage                    storage;
  protected STATUS                      status;
  protected OIntent                     currentIntent;

  private ODatabaseRecord               databaseOwner;
  private final Map<String, Object>     properties = new HashMap<String, Object>();
  private final List<ODatabaseListener> listeners  = new ArrayList<ODatabaseListener>();

  public ODatabaseRaw(final String iURL) {
    try {
      url = iURL.replace('\\', '/');
      status = STATUS.CLOSED;

      // SET DEFAULT PROPERTIES
      setProperty("fetch-max", 50);

    } catch (Throwable t) {
      throw new ODatabaseException("Error on opening database '" + iURL + "'", t);
    }
  }

  public <DB extends ODatabase> DB open(final String iUserName, final String iUserPassword) {
    try {
      if (status == STATUS.OPEN)
        throw new IllegalStateException("Database " + getName() + " is already open");

      if (storage == null)
        storage = Orient.instance().loadStorage(url);
      storage.open(iUserName, iUserPassword, properties);

      status = STATUS.OPEN;

      // WAKE UP DB LIFECYCLE LISTENER
      for (Iterator<ODatabaseLifecycleListener> it = Orient.instance().getDbLifecycleListeners(); it.hasNext();)
        it.next().onOpen(getDatabaseOwner());

      // WAKE UP LISTENERS
      for (ODatabaseListener listener : listeners)
        try {
          listener.onOpen(this);
        } catch (Throwable t) {
        }

    } catch (OException e) {
      // PASS THROUGH
      throw e;
    } catch (Exception e) {
      throw new ODatabaseException("Cannot open database", e);
    }
    return (DB) this;
  }

  public <DB extends ODatabase> DB create() {
    try {
      if (status == STATUS.OPEN)
        throw new IllegalStateException("Database " + getName() + " is already open");

      if (storage == null)
        storage = Orient.instance().loadStorage(url);
      storage.create(properties);

      // WAKE UP DB LIFECYCLE LISTENER
      for (Iterator<ODatabaseLifecycleListener> it = Orient.instance().getDbLifecycleListeners(); it.hasNext();)
        it.next().onOpen(getDatabaseOwner());

      // WAKE UP LISTENERS
      for (ODatabaseListener listener : listeners)
        try {
          listener.onCreate(this);
        } catch (Throwable t) {
        }

      status = STATUS.OPEN;
    } catch (Exception e) {
      throw new ODatabaseException("Cannot create database", e);
    }
    return (DB) this;
  }

  /**
   * Deprecated, use #drop() instead.
   * 
   * @see #drop()
   */
  @Deprecated
  public void delete() {
    drop();
  }

  public void drop() {
    final List<ODatabaseListener> tmpListeners = new ArrayList<ODatabaseListener>(listeners);
    close();

    try {
      if (storage == null)
        storage = Orient.instance().loadStorage(url);

      storage.delete();
      storage = null;

      // WAKE UP LISTENERS
      for (ODatabaseListener listener : tmpListeners)
        try {
          listener.onDelete(this);
        } catch (Throwable t) {
        }

      status = STATUS.CLOSED;
      ODatabaseRecordThreadLocal.INSTANCE.set(null);

    } catch (OException e) {
      // PASS THROUGH
      throw e;
    } catch (Exception e) {
      throw new ODatabaseException("Cannot delete database", e);
    }
  }

  public void reload() {
    storage.reload();
  }

  public STATUS getStatus() {
    return status;
  }

  public <DB extends ODatabase> DB setStatus(final STATUS status) {
    this.status = status;
    return (DB) this;
  }

  public <DB extends ODatabase> DB setDefaultClusterId(final int iDefClusterId) {
    storage.setDefaultClusterId(iDefClusterId);
    return (DB) this;
  }

  public boolean exists() {
    if (status == STATUS.OPEN)
      return true;

    if (storage == null)
      storage = Orient.instance().loadStorage(url);

    return storage.exists();
  }

  public long countClusterElements(final String iClusterName) {
    final int clusterId = getClusterIdByName(iClusterName);
    if (clusterId < 0)
      throw new IllegalArgumentException("Cluster '" + iClusterName + "' was not found");
    return storage.count(clusterId);
  }

  public long countClusterElements(final int iClusterId) {
    return storage.count(iClusterId);
  }

  public long countClusterElements(final int[] iClusterIds) {
    return storage.count(iClusterIds);
  }

  public ORawBuffer read(final ORecordId iRid, final String iFetchPlan, final boolean iIgnoreCache) {
    if (!iRid.isValid())
      return null;

    OFetchHelper.checkFetchPlanValid(iFetchPlan);

    try {
      return storage.readRecord(iRid, iFetchPlan, iIgnoreCache, null);

    } catch (Throwable t) {
      if (iRid.isTemporary())
        throw new ODatabaseException("Error on retrieving record using temporary RecordId: " + iRid, t);
      else
        throw new ODatabaseException("Error on retrieving record " + iRid + " (cluster: "
            + storage.getPhysicalClusterNameById(iRid.clusterId) + ")", t);
    }
  }

  public int save(final int iDataSegmentId, final ORecordId iRid, final byte[] iContent, final int iVersion,
      final byte iRecordType, final int iMode, final ORecordCallback<? extends Number> iCallBack) {
    // CHECK IF RECORD TYPE IS SUPPORTED
    Orient.instance().getRecordFactoryManager().getRecordTypeClass(iRecordType);

    try {
      if (iRid.clusterPosition < 0) {
        // CREATE
        return storage
            .createRecord(iDataSegmentId, iRid, iContent, iVersion, iRecordType, iMode, (ORecordCallback<Long>) iCallBack).recordVersion;

      } else {
        // UPDATE
        return storage.updateRecord(iRid, iContent, iVersion, iRecordType, iMode, (ORecordCallback<Integer>) iCallBack);
      }
    } catch (OException e) {
      // PASS THROUGH
      throw e;
    } catch (Throwable t) {
      throw new ODatabaseException("Error on saving record " + iRid, t);
    }
  }

  public void delete(final ORecordId iRid, final int iVersion, final boolean iRequired, final int iMode) {
    try {
      if (!storage.deleteRecord(iRid, iVersion, iMode, null) && iRequired)
        throw new ORecordNotFoundException("The record with id " + iRid + " was not found");

    } catch (OException e) {
      // PASS THROUGH
      throw e;
    } catch (Exception e) {
      OLogManager.instance().exception("Error on deleting record " + iRid, e, ODatabaseException.class);
    }
  }

  public OStorage getStorage() {
    return storage;
  }

  public void replaceStorage(final OStorage iNewStorage) {
    storage = iNewStorage;
  }

  public boolean isClosed() {
    return status == STATUS.CLOSED || storage.isClosed();
  }

  public String getName() {
    return storage != null ? storage.getName() : url;
  }

  public String getURL() {
    return url != null ? url : storage.getURL();
  }

  public int getDataSegmentIdByName(final String iDataSegmentName) {
    return storage.getDataSegmentIdByName(iDataSegmentName);
  }

  public String getDataSegmentNameById(final int dataSegmentId) {
    return storage.getDataSegmentById(dataSegmentId).getName();
  }

  public int getClusters() {
    return storage.getClusters();
  }

  public boolean existsCluster(final String iClusterName) {
    return storage.getClusterNames().contains(iClusterName);
  }

  public String getClusterType(final String iClusterName) {
    return storage.getClusterTypeByName(iClusterName);
  }

  public int getClusterIdByName(final String iClusterName) {
    return storage.getClusterIdByName(iClusterName);
  }

  public String getClusterNameById(final int iClusterId) {
    if (iClusterId == -1)
      return null;

    // PHIYSICAL CLUSTER
    return storage.getPhysicalClusterNameById(iClusterId);
  }

  public long getClusterRecordSizeById(final int iClusterId) {
    try {
      return storage.getClusterById(iClusterId).getRecordsSize();
    } catch (Exception e) {
      OLogManager.instance().exception("Error on reading records size for cluster with id '" + iClusterId + "'", e,
          ODatabaseException.class);
    }
    return 0l;
  }

  public long getClusterRecordSizeByName(final String iClusterName) {
    try {
      return storage.getClusterById(getClusterIdByName(iClusterName)).getRecordsSize();
    } catch (Exception e) {
      OLogManager.instance().exception("Error on reading records size for cluster '" + iClusterName + "'", e,
          ODatabaseException.class);
    }
    return 0l;
  }

  public int addCluster(String iClusterName, CLUSTER_TYPE iType, Object... iParameters) {
    return addCluster(iType.toString(), iClusterName, null, null, iParameters);
  }

  public int addCluster(final String iType, final String iClusterName, final String iLocation, final String iDataSegmentName,
      final Object... iParameters) {
    return storage.addCluster(iType, iClusterName, iLocation, iDataSegmentName, iParameters);
  }

  public int addPhysicalCluster(final String iClusterName, final String iLocation, final int iStartSize) {
    return storage.addCluster(OStorage.CLUSTER_TYPE.PHYSICAL.toString(), iClusterName, null, null, iLocation, iStartSize);
  }

  public boolean dropCluster(final String iClusterName) {
    return storage.dropCluster(iClusterName);
  }

  public boolean dropCluster(int iClusterId) {
    return storage.dropCluster(iClusterId);
  }

  public int addDataSegment(final String iSegmentName, final String iLocation) {
    return storage.addDataSegment(iSegmentName, iLocation);
  }

  public boolean dropDataSegment(final String iName) {
    return storage.dropDataSegment(iName);
  }

  public Collection<String> getClusterNames() {
    return storage.getClusterNames();
  }

  /**
   * Returns always null
   * 
   * @return
   */
  public OLevel1RecordCache getLevel1Cache() {
    return null;
  }

  public int getDefaultClusterId() {
    return storage.getDefaultClusterId();
  }

  public boolean declareIntent(final OIntent iIntent) {
    if (currentIntent != null) {
      if (iIntent != null && iIntent.getClass().equals(currentIntent.getClass()))
        // SAME INTENT: JUMP IT
        return false;

      // END CURRENT INTENT
      currentIntent.end(this);
    }

    currentIntent = iIntent;

    if (iIntent != null)
      iIntent.begin(this);

    return true;
  }

  public ODatabaseRecord getDatabaseOwner() {
    return databaseOwner;
  }

  public ODatabaseRaw setOwner(final ODatabaseRecord iOwner) {
    databaseOwner = iOwner;
    return this;
  }

  public Object setProperty(final String iName, final Object iValue) {
    if (iValue == null)
      return properties.remove(iName.toLowerCase());
    else
      return properties.put(iName.toLowerCase(), iValue);
  }

  public Object getProperty(final String iName) {
    return properties.get(iName.toLowerCase());
  }

  public Iterator<Entry<String, Object>> getProperties() {
    return properties.entrySet().iterator();
  }

  public void registerListener(final ODatabaseListener iListener) {
    if (!listeners.contains(iListener))
      listeners.add(iListener);
  }

  public void unregisterListener(final ODatabaseListener iListener) {
    for (int i = 0; i < listeners.size(); ++i)
      if (listeners.get(i) == iListener) {
        listeners.remove(i);
        break;
      }
  }

  public List<ODatabaseListener> getListeners() {
    return listeners;
  }

  public OLevel2RecordCache getLevel2Cache() {
    return storage.getLevel2Cache();
  }

  public void close() {
    if (status != STATUS.OPEN)
      return;

    if (currentIntent != null) {
      currentIntent.end(this);
      currentIntent = null;
    }

    callOnCloseListeners();
    listeners.clear();

    if (storage != null)
      storage.close();

    storage = null;
    status = STATUS.CLOSED;
  }

  @Override
  public String toString() {
    final StringBuilder buffer = new StringBuilder();
    buffer.append("OrientDB[");
    buffer.append(url != null ? url : "?");
    buffer.append(']');
    if (getStorage() != null) {
      buffer.append(" (users: ");
      buffer.append(getStorage().getUsers());
      buffer.append(')');
    }
    return buffer.toString();
  }

  public Object get(final ATTRIBUTES iAttribute) {
    if (iAttribute == null)
      throw new IllegalArgumentException("attribute is null");

    switch (iAttribute) {
    case STATUS:
      return getStatus();
    case DEFAULTCLUSTERID:
      return getDefaultClusterId();
    case TYPE:
      ODatabaseRecord db;
      if (getDatabaseOwner() instanceof ODatabaseRecord)
        db = ((ODatabaseRecord) getDatabaseOwner());
      else
        db = new OGraphDatabase(url);

      return db.getMetadata().getSchema().existsClass("OGraphVertex");
    }

    return null;
  }

  public <DB extends ODatabase> DB set(final ATTRIBUTES iAttribute, final Object iValue) {
    if (iAttribute == null)
      throw new IllegalArgumentException("attribute is null");

    final String stringValue = iValue != null ? iValue.toString() : null;

    switch (iAttribute) {
    case STATUS:
      setStatus(STATUS.valueOf(stringValue.toUpperCase(Locale.ENGLISH)));
      break;
    case DEFAULTCLUSTERID:
      if (iValue != null) {
        if (iValue instanceof Number)
          storage.setDefaultClusterId(((Number) iValue).intValue());
        else
          storage.setDefaultClusterId(storage.getClusterIdByName(iValue.toString()));
      }
      break;
    case TYPE:
      if (stringValue.equalsIgnoreCase("graph")) {
        if (getDatabaseOwner() instanceof OGraphDatabase)
          ((OGraphDatabase) getDatabaseOwner()).checkForGraphSchema();
        else if (getDatabaseOwner() instanceof ODatabaseRecordTx)
          new OGraphDatabase((ODatabaseRecordTx) getDatabaseOwner()).checkForGraphSchema();
        else
          new OGraphDatabase(url).checkForGraphSchema();
      } else
        throw new IllegalArgumentException("Database type '" + stringValue + "' is not supported");

      break;

    default:
      throw new IllegalArgumentException("Option '" + iAttribute + "' not supported on alter database");

    }

    return (DB) this;
  }

  public <V> V callInLock(Callable<V> iCallable, boolean iExclusiveLock) {
    return storage.callInLock(iCallable, iExclusiveLock);
  }

  public void callOnCloseListeners() {
    // WAKE UP DB LIFECYCLE LISTENER
    for (Iterator<ODatabaseLifecycleListener> it = Orient.instance().getDbLifecycleListeners(); it.hasNext();)
      it.next().onClose(getDatabaseOwner());

    // WAKE UP LISTENERS
    for (ODatabaseListener listener : new ArrayList<ODatabaseListener>(listeners))
      try {
        listener.onClose(getDatabaseOwner());
      } catch (Throwable t) {
        t.printStackTrace();
      }
  }

  protected boolean isClusterBoundedToClass(int iClusterId) {
    return false;
  }

  public long getSize() {
    return storage.getSize();
  }

  public void freeze() {
    final OStorageLocal storage;
    if (getStorage() instanceof OStorageLocal)
      storage = ((OStorageLocal) getStorage());
    else {
      OLogManager.instance().error(this, "We can not freeze non local storage.");
      return;
    }

    storage.freeze(false);
  }

  public void freeze(boolean throwException) {
    final OStorageLocal storage;
    if (getStorage() instanceof OStorageLocal)
      storage = ((OStorageLocal) getStorage());
    else {
      OLogManager.instance().error(this, "We can not freeze non local storage.");
      return;
    }

    storage.freeze(throwException);
  }

  public void release() {
    final OStorageLocal storage;
    if (getStorage() instanceof OStorageLocal)
      storage = ((OStorageLocal) getStorage());
    else {
      OLogManager.instance().error(this, "We can not freeze non local storage.");
      return;
    }

    storage.release();
  }
}
