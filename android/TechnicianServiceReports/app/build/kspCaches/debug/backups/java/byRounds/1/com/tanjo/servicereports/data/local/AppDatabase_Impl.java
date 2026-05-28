package com.tanjo.servicereports.data.local;

import androidx.annotation.NonNull;
import androidx.room.DatabaseConfiguration;
import androidx.room.InvalidationTracker;
import androidx.room.RoomDatabase;
import androidx.room.RoomOpenHelper;
import androidx.room.migration.AutoMigrationSpec;
import androidx.room.migration.Migration;
import androidx.room.util.DBUtil;
import androidx.room.util.TableInfo;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteOpenHelper;
import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.Generated;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class AppDatabase_Impl extends AppDatabase {
  private volatile ServiceReportDao _serviceReportDao;

  @Override
  @NonNull
  protected SupportSQLiteOpenHelper createOpenHelper(@NonNull final DatabaseConfiguration config) {
    final SupportSQLiteOpenHelper.Callback _openCallback = new RoomOpenHelper(config, new RoomOpenHelper.Delegate(1) {
      @Override
      public void createAllTables(@NonNull final SupportSQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `jobs` (`id` INTEGER NOT NULL, `jobNumber` TEXT NOT NULL, `customerId` INTEGER, `companyName` TEXT NOT NULL, `contactName` TEXT NOT NULL, `address` TEXT NOT NULL, `scheduledDate` TEXT NOT NULL, `serviceType` TEXT NOT NULL, `jobStatus` TEXT NOT NULL, `syncStatus` TEXT NOT NULL, `reportStatus` TEXT NOT NULL, `description` TEXT NOT NULL, PRIMARY KEY(`id`))");
        db.execSQL("CREATE TABLE IF NOT EXISTS `service_reports` (`localId` TEXT NOT NULL, `odooId` INTEGER, `mobileExternalId` TEXT NOT NULL, `jobId` INTEGER, `reportNumber` TEXT NOT NULL, `customerId` INTEGER, `customerName` TEXT NOT NULL, `companyName` TEXT NOT NULL, `contactName` TEXT NOT NULL, `address` TEXT NOT NULL, `serviceDate` TEXT NOT NULL, `arrivalTime` TEXT NOT NULL, `departureTime` TEXT NOT NULL, `laborHours` REAL NOT NULL, `vehicle` TEXT NOT NULL, `poReference` TEXT NOT NULL, `serviceType` TEXT NOT NULL, `originalReportNumber` TEXT NOT NULL, `make` TEXT NOT NULL, `model` TEXT NOT NULL, `kva` TEXT NOT NULL, `equipmentType` TEXT NOT NULL, `serialNumber` TEXT NOT NULL, `load` TEXT NOT NULL, `inputVoltage` TEXT NOT NULL, `outputVoltage` TEXT NOT NULL, `systemDown` INTEGER NOT NULL, `batteryManufacturer` TEXT NOT NULL, `batteryType` TEXT NOT NULL, `batteryRating` TEXT NOT NULL, `batteryQuantity` INTEGER NOT NULL, `problemReported` TEXT NOT NULL, `defectsFound` TEXT NOT NULL, `correctiveAction` TEXT NOT NULL, `recommendations` TEXT NOT NULL, `statusOfService` TEXT NOT NULL, `customerSignaturePath` TEXT NOT NULL, `technicianSignaturePath` TEXT NOT NULL, `technicianName` TEXT NOT NULL, `signatureDateTime` TEXT NOT NULL, `state` TEXT NOT NULL, `syncStatus` TEXT NOT NULL, PRIMARY KEY(`localId`))");
        db.execSQL("CREATE TABLE IF NOT EXISTS `parts` (`id` TEXT NOT NULL, `reportLocalId` TEXT NOT NULL, `partName` TEXT NOT NULL, `serialNumber` TEXT NOT NULL, `quantity` REAL NOT NULL, `conditionType` TEXT NOT NULL, `invoiceable` INTEGER NOT NULL, `notes` TEXT NOT NULL, PRIMARY KEY(`id`))");
        db.execSQL("CREATE TABLE IF NOT EXISTS `attachments` (`id` TEXT NOT NULL, `reportLocalId` TEXT NOT NULL, `filePath` TEXT NOT NULL, `mimeType` TEXT NOT NULL, `category` TEXT NOT NULL, PRIMARY KEY(`id`))");
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)");
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'b1875645764bf18f7d2a2848b818860a')");
      }

      @Override
      public void dropAllTables(@NonNull final SupportSQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS `jobs`");
        db.execSQL("DROP TABLE IF EXISTS `service_reports`");
        db.execSQL("DROP TABLE IF EXISTS `parts`");
        db.execSQL("DROP TABLE IF EXISTS `attachments`");
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onDestructiveMigration(db);
          }
        }
      }

      @Override
      public void onCreate(@NonNull final SupportSQLiteDatabase db) {
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onCreate(db);
          }
        }
      }

      @Override
      public void onOpen(@NonNull final SupportSQLiteDatabase db) {
        mDatabase = db;
        internalInitInvalidationTracker(db);
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onOpen(db);
          }
        }
      }

      @Override
      public void onPreMigrate(@NonNull final SupportSQLiteDatabase db) {
        DBUtil.dropFtsSyncTriggers(db);
      }

      @Override
      public void onPostMigrate(@NonNull final SupportSQLiteDatabase db) {
      }

      @Override
      @NonNull
      public RoomOpenHelper.ValidationResult onValidateSchema(
          @NonNull final SupportSQLiteDatabase db) {
        final HashMap<String, TableInfo.Column> _columnsJobs = new HashMap<String, TableInfo.Column>(12);
        _columnsJobs.put("id", new TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsJobs.put("jobNumber", new TableInfo.Column("jobNumber", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsJobs.put("customerId", new TableInfo.Column("customerId", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsJobs.put("companyName", new TableInfo.Column("companyName", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsJobs.put("contactName", new TableInfo.Column("contactName", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsJobs.put("address", new TableInfo.Column("address", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsJobs.put("scheduledDate", new TableInfo.Column("scheduledDate", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsJobs.put("serviceType", new TableInfo.Column("serviceType", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsJobs.put("jobStatus", new TableInfo.Column("jobStatus", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsJobs.put("syncStatus", new TableInfo.Column("syncStatus", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsJobs.put("reportStatus", new TableInfo.Column("reportStatus", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsJobs.put("description", new TableInfo.Column("description", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysJobs = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesJobs = new HashSet<TableInfo.Index>(0);
        final TableInfo _infoJobs = new TableInfo("jobs", _columnsJobs, _foreignKeysJobs, _indicesJobs);
        final TableInfo _existingJobs = TableInfo.read(db, "jobs");
        if (!_infoJobs.equals(_existingJobs)) {
          return new RoomOpenHelper.ValidationResult(false, "jobs(com.tanjo.servicereports.data.local.JobEntity).\n"
                  + " Expected:\n" + _infoJobs + "\n"
                  + " Found:\n" + _existingJobs);
        }
        final HashMap<String, TableInfo.Column> _columnsServiceReports = new HashMap<String, TableInfo.Column>(42);
        _columnsServiceReports.put("localId", new TableInfo.Column("localId", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsServiceReports.put("odooId", new TableInfo.Column("odooId", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsServiceReports.put("mobileExternalId", new TableInfo.Column("mobileExternalId", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsServiceReports.put("jobId", new TableInfo.Column("jobId", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsServiceReports.put("reportNumber", new TableInfo.Column("reportNumber", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsServiceReports.put("customerId", new TableInfo.Column("customerId", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsServiceReports.put("customerName", new TableInfo.Column("customerName", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsServiceReports.put("companyName", new TableInfo.Column("companyName", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsServiceReports.put("contactName", new TableInfo.Column("contactName", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsServiceReports.put("address", new TableInfo.Column("address", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsServiceReports.put("serviceDate", new TableInfo.Column("serviceDate", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsServiceReports.put("arrivalTime", new TableInfo.Column("arrivalTime", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsServiceReports.put("departureTime", new TableInfo.Column("departureTime", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsServiceReports.put("laborHours", new TableInfo.Column("laborHours", "REAL", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsServiceReports.put("vehicle", new TableInfo.Column("vehicle", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsServiceReports.put("poReference", new TableInfo.Column("poReference", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsServiceReports.put("serviceType", new TableInfo.Column("serviceType", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsServiceReports.put("originalReportNumber", new TableInfo.Column("originalReportNumber", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsServiceReports.put("make", new TableInfo.Column("make", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsServiceReports.put("model", new TableInfo.Column("model", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsServiceReports.put("kva", new TableInfo.Column("kva", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsServiceReports.put("equipmentType", new TableInfo.Column("equipmentType", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsServiceReports.put("serialNumber", new TableInfo.Column("serialNumber", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsServiceReports.put("load", new TableInfo.Column("load", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsServiceReports.put("inputVoltage", new TableInfo.Column("inputVoltage", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsServiceReports.put("outputVoltage", new TableInfo.Column("outputVoltage", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsServiceReports.put("systemDown", new TableInfo.Column("systemDown", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsServiceReports.put("batteryManufacturer", new TableInfo.Column("batteryManufacturer", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsServiceReports.put("batteryType", new TableInfo.Column("batteryType", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsServiceReports.put("batteryRating", new TableInfo.Column("batteryRating", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsServiceReports.put("batteryQuantity", new TableInfo.Column("batteryQuantity", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsServiceReports.put("problemReported", new TableInfo.Column("problemReported", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsServiceReports.put("defectsFound", new TableInfo.Column("defectsFound", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsServiceReports.put("correctiveAction", new TableInfo.Column("correctiveAction", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsServiceReports.put("recommendations", new TableInfo.Column("recommendations", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsServiceReports.put("statusOfService", new TableInfo.Column("statusOfService", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsServiceReports.put("customerSignaturePath", new TableInfo.Column("customerSignaturePath", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsServiceReports.put("technicianSignaturePath", new TableInfo.Column("technicianSignaturePath", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsServiceReports.put("technicianName", new TableInfo.Column("technicianName", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsServiceReports.put("signatureDateTime", new TableInfo.Column("signatureDateTime", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsServiceReports.put("state", new TableInfo.Column("state", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsServiceReports.put("syncStatus", new TableInfo.Column("syncStatus", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysServiceReports = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesServiceReports = new HashSet<TableInfo.Index>(0);
        final TableInfo _infoServiceReports = new TableInfo("service_reports", _columnsServiceReports, _foreignKeysServiceReports, _indicesServiceReports);
        final TableInfo _existingServiceReports = TableInfo.read(db, "service_reports");
        if (!_infoServiceReports.equals(_existingServiceReports)) {
          return new RoomOpenHelper.ValidationResult(false, "service_reports(com.tanjo.servicereports.data.local.ServiceReportEntity).\n"
                  + " Expected:\n" + _infoServiceReports + "\n"
                  + " Found:\n" + _existingServiceReports);
        }
        final HashMap<String, TableInfo.Column> _columnsParts = new HashMap<String, TableInfo.Column>(8);
        _columnsParts.put("id", new TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsParts.put("reportLocalId", new TableInfo.Column("reportLocalId", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsParts.put("partName", new TableInfo.Column("partName", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsParts.put("serialNumber", new TableInfo.Column("serialNumber", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsParts.put("quantity", new TableInfo.Column("quantity", "REAL", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsParts.put("conditionType", new TableInfo.Column("conditionType", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsParts.put("invoiceable", new TableInfo.Column("invoiceable", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsParts.put("notes", new TableInfo.Column("notes", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysParts = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesParts = new HashSet<TableInfo.Index>(0);
        final TableInfo _infoParts = new TableInfo("parts", _columnsParts, _foreignKeysParts, _indicesParts);
        final TableInfo _existingParts = TableInfo.read(db, "parts");
        if (!_infoParts.equals(_existingParts)) {
          return new RoomOpenHelper.ValidationResult(false, "parts(com.tanjo.servicereports.data.local.PartEntity).\n"
                  + " Expected:\n" + _infoParts + "\n"
                  + " Found:\n" + _existingParts);
        }
        final HashMap<String, TableInfo.Column> _columnsAttachments = new HashMap<String, TableInfo.Column>(5);
        _columnsAttachments.put("id", new TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAttachments.put("reportLocalId", new TableInfo.Column("reportLocalId", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAttachments.put("filePath", new TableInfo.Column("filePath", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAttachments.put("mimeType", new TableInfo.Column("mimeType", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAttachments.put("category", new TableInfo.Column("category", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysAttachments = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesAttachments = new HashSet<TableInfo.Index>(0);
        final TableInfo _infoAttachments = new TableInfo("attachments", _columnsAttachments, _foreignKeysAttachments, _indicesAttachments);
        final TableInfo _existingAttachments = TableInfo.read(db, "attachments");
        if (!_infoAttachments.equals(_existingAttachments)) {
          return new RoomOpenHelper.ValidationResult(false, "attachments(com.tanjo.servicereports.data.local.AttachmentEntity).\n"
                  + " Expected:\n" + _infoAttachments + "\n"
                  + " Found:\n" + _existingAttachments);
        }
        return new RoomOpenHelper.ValidationResult(true, null);
      }
    }, "b1875645764bf18f7d2a2848b818860a", "c23005ac4a07833f9650e7bbbf5b15e8");
    final SupportSQLiteOpenHelper.Configuration _sqliteConfig = SupportSQLiteOpenHelper.Configuration.builder(config.context).name(config.name).callback(_openCallback).build();
    final SupportSQLiteOpenHelper _helper = config.sqliteOpenHelperFactory.create(_sqliteConfig);
    return _helper;
  }

  @Override
  @NonNull
  protected InvalidationTracker createInvalidationTracker() {
    final HashMap<String, String> _shadowTablesMap = new HashMap<String, String>(0);
    final HashMap<String, Set<String>> _viewTables = new HashMap<String, Set<String>>(0);
    return new InvalidationTracker(this, _shadowTablesMap, _viewTables, "jobs","service_reports","parts","attachments");
  }

  @Override
  public void clearAllTables() {
    super.assertNotMainThread();
    final SupportSQLiteDatabase _db = super.getOpenHelper().getWritableDatabase();
    try {
      super.beginTransaction();
      _db.execSQL("DELETE FROM `jobs`");
      _db.execSQL("DELETE FROM `service_reports`");
      _db.execSQL("DELETE FROM `parts`");
      _db.execSQL("DELETE FROM `attachments`");
      super.setTransactionSuccessful();
    } finally {
      super.endTransaction();
      _db.query("PRAGMA wal_checkpoint(FULL)").close();
      if (!_db.inTransaction()) {
        _db.execSQL("VACUUM");
      }
    }
  }

  @Override
  @NonNull
  protected Map<Class<?>, List<Class<?>>> getRequiredTypeConverters() {
    final HashMap<Class<?>, List<Class<?>>> _typeConvertersMap = new HashMap<Class<?>, List<Class<?>>>();
    _typeConvertersMap.put(ServiceReportDao.class, ServiceReportDao_Impl.getRequiredConverters());
    return _typeConvertersMap;
  }

  @Override
  @NonNull
  public Set<Class<? extends AutoMigrationSpec>> getRequiredAutoMigrationSpecs() {
    final HashSet<Class<? extends AutoMigrationSpec>> _autoMigrationSpecsSet = new HashSet<Class<? extends AutoMigrationSpec>>();
    return _autoMigrationSpecsSet;
  }

  @Override
  @NonNull
  public List<Migration> getAutoMigrations(
      @NonNull final Map<Class<? extends AutoMigrationSpec>, AutoMigrationSpec> autoMigrationSpecs) {
    final List<Migration> _autoMigrations = new ArrayList<Migration>();
    return _autoMigrations;
  }

  @Override
  public ServiceReportDao dao() {
    if (_serviceReportDao != null) {
      return _serviceReportDao;
    } else {
      synchronized(this) {
        if(_serviceReportDao == null) {
          _serviceReportDao = new ServiceReportDao_Impl(this);
        }
        return _serviceReportDao;
      }
    }
  }
}
