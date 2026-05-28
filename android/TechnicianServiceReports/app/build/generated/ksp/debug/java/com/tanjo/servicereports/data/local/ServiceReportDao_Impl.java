package com.tanjo.servicereports.data.local;

import android.database.Cursor;
import android.os.CancellationSignal;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityDeletionOrUpdateAdapter;
import androidx.room.EntityInsertionAdapter;
import androidx.room.EntityUpsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.SharedSQLiteStatement;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import java.lang.Class;
import java.lang.Exception;
import java.lang.Long;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import javax.annotation.processing.Generated;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class ServiceReportDao_Impl implements ServiceReportDao {
  private final RoomDatabase __db;

  private final SharedSQLiteStatement __preparedStmtOfDeleteJobs;

  private final SharedSQLiteStatement __preparedStmtOfDeletePartsForReport;

  private final SharedSQLiteStatement __preparedStmtOfDeletePart;

  private final EntityUpsertionAdapter<JobEntity> __upsertionAdapterOfJobEntity;

  private final EntityUpsertionAdapter<ServiceReportEntity> __upsertionAdapterOfServiceReportEntity;

  private final EntityUpsertionAdapter<PartEntity> __upsertionAdapterOfPartEntity;

  private final EntityUpsertionAdapter<AttachmentEntity> __upsertionAdapterOfAttachmentEntity;

  public ServiceReportDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__preparedStmtOfDeleteJobs = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "delete from jobs";
        return _query;
      }
    };
    this.__preparedStmtOfDeletePartsForReport = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "delete from parts where reportLocalId = ?";
        return _query;
      }
    };
    this.__preparedStmtOfDeletePart = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "delete from parts where id = ?";
        return _query;
      }
    };
    this.__upsertionAdapterOfJobEntity = new EntityUpsertionAdapter<JobEntity>(new EntityInsertionAdapter<JobEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT INTO `jobs` (`id`,`reportId`,`jobNumber`,`customerId`,`companyName`,`contactName`,`address`,`scheduledDate`,`serviceType`,`jobStatus`,`syncStatus`,`reportStatus`,`description`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final JobEntity entity) {
        statement.bindLong(1, entity.getId());
        if (entity.getReportId() == null) {
          statement.bindNull(2);
        } else {
          statement.bindLong(2, entity.getReportId());
        }
        statement.bindString(3, entity.getJobNumber());
        if (entity.getCustomerId() == null) {
          statement.bindNull(4);
        } else {
          statement.bindLong(4, entity.getCustomerId());
        }
        statement.bindString(5, entity.getCompanyName());
        statement.bindString(6, entity.getContactName());
        statement.bindString(7, entity.getAddress());
        statement.bindString(8, entity.getScheduledDate());
        statement.bindString(9, entity.getServiceType());
        statement.bindString(10, entity.getJobStatus());
        statement.bindString(11, entity.getSyncStatus());
        statement.bindString(12, entity.getReportStatus());
        statement.bindString(13, entity.getDescription());
      }
    }, new EntityDeletionOrUpdateAdapter<JobEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "UPDATE `jobs` SET `id` = ?,`reportId` = ?,`jobNumber` = ?,`customerId` = ?,`companyName` = ?,`contactName` = ?,`address` = ?,`scheduledDate` = ?,`serviceType` = ?,`jobStatus` = ?,`syncStatus` = ?,`reportStatus` = ?,`description` = ? WHERE `id` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final JobEntity entity) {
        statement.bindLong(1, entity.getId());
        if (entity.getReportId() == null) {
          statement.bindNull(2);
        } else {
          statement.bindLong(2, entity.getReportId());
        }
        statement.bindString(3, entity.getJobNumber());
        if (entity.getCustomerId() == null) {
          statement.bindNull(4);
        } else {
          statement.bindLong(4, entity.getCustomerId());
        }
        statement.bindString(5, entity.getCompanyName());
        statement.bindString(6, entity.getContactName());
        statement.bindString(7, entity.getAddress());
        statement.bindString(8, entity.getScheduledDate());
        statement.bindString(9, entity.getServiceType());
        statement.bindString(10, entity.getJobStatus());
        statement.bindString(11, entity.getSyncStatus());
        statement.bindString(12, entity.getReportStatus());
        statement.bindString(13, entity.getDescription());
        statement.bindLong(14, entity.getId());
      }
    });
    this.__upsertionAdapterOfServiceReportEntity = new EntityUpsertionAdapter<ServiceReportEntity>(new EntityInsertionAdapter<ServiceReportEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT INTO `service_reports` (`localId`,`odooId`,`mobileExternalId`,`jobId`,`reportNumber`,`customerId`,`customerName`,`companyName`,`contactName`,`address`,`serviceDate`,`arrivalTime`,`departureTime`,`laborHours`,`vehicle`,`poReference`,`serviceType`,`originalReportNumber`,`make`,`model`,`kva`,`equipmentType`,`serialNumber`,`load`,`inputVoltage`,`outputVoltage`,`systemDown`,`batteryManufacturer`,`batteryType`,`batteryRating`,`batteryQuantity`,`problemReported`,`defectsFound`,`correctiveAction`,`recommendations`,`techniciansOnSite`,`statusOfService`,`customerSignaturePath`,`technicianSignaturePath`,`technicianName`,`signatureDateTime`,`state`,`syncStatus`,`syncError`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final ServiceReportEntity entity) {
        statement.bindString(1, entity.getLocalId());
        if (entity.getOdooId() == null) {
          statement.bindNull(2);
        } else {
          statement.bindLong(2, entity.getOdooId());
        }
        statement.bindString(3, entity.getMobileExternalId());
        if (entity.getJobId() == null) {
          statement.bindNull(4);
        } else {
          statement.bindLong(4, entity.getJobId());
        }
        statement.bindString(5, entity.getReportNumber());
        if (entity.getCustomerId() == null) {
          statement.bindNull(6);
        } else {
          statement.bindLong(6, entity.getCustomerId());
        }
        statement.bindString(7, entity.getCustomerName());
        statement.bindString(8, entity.getCompanyName());
        statement.bindString(9, entity.getContactName());
        statement.bindString(10, entity.getAddress());
        statement.bindString(11, entity.getServiceDate());
        statement.bindString(12, entity.getArrivalTime());
        statement.bindString(13, entity.getDepartureTime());
        statement.bindDouble(14, entity.getLaborHours());
        statement.bindString(15, entity.getVehicle());
        statement.bindString(16, entity.getPoReference());
        statement.bindString(17, entity.getServiceType());
        statement.bindString(18, entity.getOriginalReportNumber());
        statement.bindString(19, entity.getMake());
        statement.bindString(20, entity.getModel());
        statement.bindString(21, entity.getKva());
        statement.bindString(22, entity.getEquipmentType());
        statement.bindString(23, entity.getSerialNumber());
        statement.bindString(24, entity.getLoad());
        statement.bindString(25, entity.getInputVoltage());
        statement.bindString(26, entity.getOutputVoltage());
        final int _tmp = entity.getSystemDown() ? 1 : 0;
        statement.bindLong(27, _tmp);
        statement.bindString(28, entity.getBatteryManufacturer());
        statement.bindString(29, entity.getBatteryType());
        statement.bindString(30, entity.getBatteryRating());
        statement.bindLong(31, entity.getBatteryQuantity());
        statement.bindString(32, entity.getProblemReported());
        statement.bindString(33, entity.getDefectsFound());
        statement.bindString(34, entity.getCorrectiveAction());
        statement.bindString(35, entity.getRecommendations());
        statement.bindString(36, entity.getTechniciansOnSite());
        statement.bindString(37, entity.getStatusOfService());
        statement.bindString(38, entity.getCustomerSignaturePath());
        statement.bindString(39, entity.getTechnicianSignaturePath());
        statement.bindString(40, entity.getTechnicianName());
        statement.bindString(41, entity.getSignatureDateTime());
        statement.bindString(42, entity.getState());
        statement.bindString(43, entity.getSyncStatus());
        statement.bindString(44, entity.getSyncError());
      }
    }, new EntityDeletionOrUpdateAdapter<ServiceReportEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "UPDATE `service_reports` SET `localId` = ?,`odooId` = ?,`mobileExternalId` = ?,`jobId` = ?,`reportNumber` = ?,`customerId` = ?,`customerName` = ?,`companyName` = ?,`contactName` = ?,`address` = ?,`serviceDate` = ?,`arrivalTime` = ?,`departureTime` = ?,`laborHours` = ?,`vehicle` = ?,`poReference` = ?,`serviceType` = ?,`originalReportNumber` = ?,`make` = ?,`model` = ?,`kva` = ?,`equipmentType` = ?,`serialNumber` = ?,`load` = ?,`inputVoltage` = ?,`outputVoltage` = ?,`systemDown` = ?,`batteryManufacturer` = ?,`batteryType` = ?,`batteryRating` = ?,`batteryQuantity` = ?,`problemReported` = ?,`defectsFound` = ?,`correctiveAction` = ?,`recommendations` = ?,`techniciansOnSite` = ?,`statusOfService` = ?,`customerSignaturePath` = ?,`technicianSignaturePath` = ?,`technicianName` = ?,`signatureDateTime` = ?,`state` = ?,`syncStatus` = ?,`syncError` = ? WHERE `localId` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final ServiceReportEntity entity) {
        statement.bindString(1, entity.getLocalId());
        if (entity.getOdooId() == null) {
          statement.bindNull(2);
        } else {
          statement.bindLong(2, entity.getOdooId());
        }
        statement.bindString(3, entity.getMobileExternalId());
        if (entity.getJobId() == null) {
          statement.bindNull(4);
        } else {
          statement.bindLong(4, entity.getJobId());
        }
        statement.bindString(5, entity.getReportNumber());
        if (entity.getCustomerId() == null) {
          statement.bindNull(6);
        } else {
          statement.bindLong(6, entity.getCustomerId());
        }
        statement.bindString(7, entity.getCustomerName());
        statement.bindString(8, entity.getCompanyName());
        statement.bindString(9, entity.getContactName());
        statement.bindString(10, entity.getAddress());
        statement.bindString(11, entity.getServiceDate());
        statement.bindString(12, entity.getArrivalTime());
        statement.bindString(13, entity.getDepartureTime());
        statement.bindDouble(14, entity.getLaborHours());
        statement.bindString(15, entity.getVehicle());
        statement.bindString(16, entity.getPoReference());
        statement.bindString(17, entity.getServiceType());
        statement.bindString(18, entity.getOriginalReportNumber());
        statement.bindString(19, entity.getMake());
        statement.bindString(20, entity.getModel());
        statement.bindString(21, entity.getKva());
        statement.bindString(22, entity.getEquipmentType());
        statement.bindString(23, entity.getSerialNumber());
        statement.bindString(24, entity.getLoad());
        statement.bindString(25, entity.getInputVoltage());
        statement.bindString(26, entity.getOutputVoltage());
        final int _tmp = entity.getSystemDown() ? 1 : 0;
        statement.bindLong(27, _tmp);
        statement.bindString(28, entity.getBatteryManufacturer());
        statement.bindString(29, entity.getBatteryType());
        statement.bindString(30, entity.getBatteryRating());
        statement.bindLong(31, entity.getBatteryQuantity());
        statement.bindString(32, entity.getProblemReported());
        statement.bindString(33, entity.getDefectsFound());
        statement.bindString(34, entity.getCorrectiveAction());
        statement.bindString(35, entity.getRecommendations());
        statement.bindString(36, entity.getTechniciansOnSite());
        statement.bindString(37, entity.getStatusOfService());
        statement.bindString(38, entity.getCustomerSignaturePath());
        statement.bindString(39, entity.getTechnicianSignaturePath());
        statement.bindString(40, entity.getTechnicianName());
        statement.bindString(41, entity.getSignatureDateTime());
        statement.bindString(42, entity.getState());
        statement.bindString(43, entity.getSyncStatus());
        statement.bindString(44, entity.getSyncError());
        statement.bindString(45, entity.getLocalId());
      }
    });
    this.__upsertionAdapterOfPartEntity = new EntityUpsertionAdapter<PartEntity>(new EntityInsertionAdapter<PartEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT INTO `parts` (`id`,`reportLocalId`,`partName`,`serialNumber`,`quantity`,`conditionType`,`invoiceable`,`notes`) VALUES (?,?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final PartEntity entity) {
        statement.bindString(1, entity.getId());
        statement.bindString(2, entity.getReportLocalId());
        statement.bindString(3, entity.getPartName());
        statement.bindString(4, entity.getSerialNumber());
        statement.bindDouble(5, entity.getQuantity());
        statement.bindString(6, entity.getConditionType());
        final int _tmp = entity.getInvoiceable() ? 1 : 0;
        statement.bindLong(7, _tmp);
        statement.bindString(8, entity.getNotes());
      }
    }, new EntityDeletionOrUpdateAdapter<PartEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "UPDATE `parts` SET `id` = ?,`reportLocalId` = ?,`partName` = ?,`serialNumber` = ?,`quantity` = ?,`conditionType` = ?,`invoiceable` = ?,`notes` = ? WHERE `id` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final PartEntity entity) {
        statement.bindString(1, entity.getId());
        statement.bindString(2, entity.getReportLocalId());
        statement.bindString(3, entity.getPartName());
        statement.bindString(4, entity.getSerialNumber());
        statement.bindDouble(5, entity.getQuantity());
        statement.bindString(6, entity.getConditionType());
        final int _tmp = entity.getInvoiceable() ? 1 : 0;
        statement.bindLong(7, _tmp);
        statement.bindString(8, entity.getNotes());
        statement.bindString(9, entity.getId());
      }
    });
    this.__upsertionAdapterOfAttachmentEntity = new EntityUpsertionAdapter<AttachmentEntity>(new EntityInsertionAdapter<AttachmentEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT INTO `attachments` (`id`,`reportLocalId`,`filePath`,`mimeType`,`category`) VALUES (?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final AttachmentEntity entity) {
        statement.bindString(1, entity.getId());
        statement.bindString(2, entity.getReportLocalId());
        statement.bindString(3, entity.getFilePath());
        statement.bindString(4, entity.getMimeType());
        statement.bindString(5, entity.getCategory());
      }
    }, new EntityDeletionOrUpdateAdapter<AttachmentEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "UPDATE `attachments` SET `id` = ?,`reportLocalId` = ?,`filePath` = ?,`mimeType` = ?,`category` = ? WHERE `id` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final AttachmentEntity entity) {
        statement.bindString(1, entity.getId());
        statement.bindString(2, entity.getReportLocalId());
        statement.bindString(3, entity.getFilePath());
        statement.bindString(4, entity.getMimeType());
        statement.bindString(5, entity.getCategory());
        statement.bindString(6, entity.getId());
      }
    });
  }

  @Override
  public Object deleteJobs(final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteJobs.acquire();
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDeleteJobs.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object deletePartsForReport(final String localId,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeletePartsForReport.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, localId);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDeletePartsForReport.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object deletePart(final String id, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeletePart.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, id);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDeletePart.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object upsertJobs(final List<JobEntity> jobs,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __upsertionAdapterOfJobEntity.upsert(jobs);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object upsertReport(final ServiceReportEntity report,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __upsertionAdapterOfServiceReportEntity.upsert(report);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object upsertPart(final PartEntity part, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __upsertionAdapterOfPartEntity.upsert(part);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object upsertAttachment(final AttachmentEntity attachment,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __upsertionAdapterOfAttachmentEntity.upsert(attachment);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<JobEntity>> observeJobs() {
    final String _sql = "select * from jobs order by scheduledDate asc";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"jobs"}, new Callable<List<JobEntity>>() {
      @Override
      @NonNull
      public List<JobEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfReportId = CursorUtil.getColumnIndexOrThrow(_cursor, "reportId");
          final int _cursorIndexOfJobNumber = CursorUtil.getColumnIndexOrThrow(_cursor, "jobNumber");
          final int _cursorIndexOfCustomerId = CursorUtil.getColumnIndexOrThrow(_cursor, "customerId");
          final int _cursorIndexOfCompanyName = CursorUtil.getColumnIndexOrThrow(_cursor, "companyName");
          final int _cursorIndexOfContactName = CursorUtil.getColumnIndexOrThrow(_cursor, "contactName");
          final int _cursorIndexOfAddress = CursorUtil.getColumnIndexOrThrow(_cursor, "address");
          final int _cursorIndexOfScheduledDate = CursorUtil.getColumnIndexOrThrow(_cursor, "scheduledDate");
          final int _cursorIndexOfServiceType = CursorUtil.getColumnIndexOrThrow(_cursor, "serviceType");
          final int _cursorIndexOfJobStatus = CursorUtil.getColumnIndexOrThrow(_cursor, "jobStatus");
          final int _cursorIndexOfSyncStatus = CursorUtil.getColumnIndexOrThrow(_cursor, "syncStatus");
          final int _cursorIndexOfReportStatus = CursorUtil.getColumnIndexOrThrow(_cursor, "reportStatus");
          final int _cursorIndexOfDescription = CursorUtil.getColumnIndexOrThrow(_cursor, "description");
          final List<JobEntity> _result = new ArrayList<JobEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final JobEntity _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final Long _tmpReportId;
            if (_cursor.isNull(_cursorIndexOfReportId)) {
              _tmpReportId = null;
            } else {
              _tmpReportId = _cursor.getLong(_cursorIndexOfReportId);
            }
            final String _tmpJobNumber;
            _tmpJobNumber = _cursor.getString(_cursorIndexOfJobNumber);
            final Long _tmpCustomerId;
            if (_cursor.isNull(_cursorIndexOfCustomerId)) {
              _tmpCustomerId = null;
            } else {
              _tmpCustomerId = _cursor.getLong(_cursorIndexOfCustomerId);
            }
            final String _tmpCompanyName;
            _tmpCompanyName = _cursor.getString(_cursorIndexOfCompanyName);
            final String _tmpContactName;
            _tmpContactName = _cursor.getString(_cursorIndexOfContactName);
            final String _tmpAddress;
            _tmpAddress = _cursor.getString(_cursorIndexOfAddress);
            final String _tmpScheduledDate;
            _tmpScheduledDate = _cursor.getString(_cursorIndexOfScheduledDate);
            final String _tmpServiceType;
            _tmpServiceType = _cursor.getString(_cursorIndexOfServiceType);
            final String _tmpJobStatus;
            _tmpJobStatus = _cursor.getString(_cursorIndexOfJobStatus);
            final String _tmpSyncStatus;
            _tmpSyncStatus = _cursor.getString(_cursorIndexOfSyncStatus);
            final String _tmpReportStatus;
            _tmpReportStatus = _cursor.getString(_cursorIndexOfReportStatus);
            final String _tmpDescription;
            _tmpDescription = _cursor.getString(_cursorIndexOfDescription);
            _item = new JobEntity(_tmpId,_tmpReportId,_tmpJobNumber,_tmpCustomerId,_tmpCompanyName,_tmpContactName,_tmpAddress,_tmpScheduledDate,_tmpServiceType,_tmpJobStatus,_tmpSyncStatus,_tmpReportStatus,_tmpDescription);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Flow<ServiceReportEntity> observeReport(final String localId) {
    final String _sql = "select * from service_reports where localId = ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindString(_argIndex, localId);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"service_reports"}, new Callable<ServiceReportEntity>() {
      @Override
      @Nullable
      public ServiceReportEntity call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfLocalId = CursorUtil.getColumnIndexOrThrow(_cursor, "localId");
          final int _cursorIndexOfOdooId = CursorUtil.getColumnIndexOrThrow(_cursor, "odooId");
          final int _cursorIndexOfMobileExternalId = CursorUtil.getColumnIndexOrThrow(_cursor, "mobileExternalId");
          final int _cursorIndexOfJobId = CursorUtil.getColumnIndexOrThrow(_cursor, "jobId");
          final int _cursorIndexOfReportNumber = CursorUtil.getColumnIndexOrThrow(_cursor, "reportNumber");
          final int _cursorIndexOfCustomerId = CursorUtil.getColumnIndexOrThrow(_cursor, "customerId");
          final int _cursorIndexOfCustomerName = CursorUtil.getColumnIndexOrThrow(_cursor, "customerName");
          final int _cursorIndexOfCompanyName = CursorUtil.getColumnIndexOrThrow(_cursor, "companyName");
          final int _cursorIndexOfContactName = CursorUtil.getColumnIndexOrThrow(_cursor, "contactName");
          final int _cursorIndexOfAddress = CursorUtil.getColumnIndexOrThrow(_cursor, "address");
          final int _cursorIndexOfServiceDate = CursorUtil.getColumnIndexOrThrow(_cursor, "serviceDate");
          final int _cursorIndexOfArrivalTime = CursorUtil.getColumnIndexOrThrow(_cursor, "arrivalTime");
          final int _cursorIndexOfDepartureTime = CursorUtil.getColumnIndexOrThrow(_cursor, "departureTime");
          final int _cursorIndexOfLaborHours = CursorUtil.getColumnIndexOrThrow(_cursor, "laborHours");
          final int _cursorIndexOfVehicle = CursorUtil.getColumnIndexOrThrow(_cursor, "vehicle");
          final int _cursorIndexOfPoReference = CursorUtil.getColumnIndexOrThrow(_cursor, "poReference");
          final int _cursorIndexOfServiceType = CursorUtil.getColumnIndexOrThrow(_cursor, "serviceType");
          final int _cursorIndexOfOriginalReportNumber = CursorUtil.getColumnIndexOrThrow(_cursor, "originalReportNumber");
          final int _cursorIndexOfMake = CursorUtil.getColumnIndexOrThrow(_cursor, "make");
          final int _cursorIndexOfModel = CursorUtil.getColumnIndexOrThrow(_cursor, "model");
          final int _cursorIndexOfKva = CursorUtil.getColumnIndexOrThrow(_cursor, "kva");
          final int _cursorIndexOfEquipmentType = CursorUtil.getColumnIndexOrThrow(_cursor, "equipmentType");
          final int _cursorIndexOfSerialNumber = CursorUtil.getColumnIndexOrThrow(_cursor, "serialNumber");
          final int _cursorIndexOfLoad = CursorUtil.getColumnIndexOrThrow(_cursor, "load");
          final int _cursorIndexOfInputVoltage = CursorUtil.getColumnIndexOrThrow(_cursor, "inputVoltage");
          final int _cursorIndexOfOutputVoltage = CursorUtil.getColumnIndexOrThrow(_cursor, "outputVoltage");
          final int _cursorIndexOfSystemDown = CursorUtil.getColumnIndexOrThrow(_cursor, "systemDown");
          final int _cursorIndexOfBatteryManufacturer = CursorUtil.getColumnIndexOrThrow(_cursor, "batteryManufacturer");
          final int _cursorIndexOfBatteryType = CursorUtil.getColumnIndexOrThrow(_cursor, "batteryType");
          final int _cursorIndexOfBatteryRating = CursorUtil.getColumnIndexOrThrow(_cursor, "batteryRating");
          final int _cursorIndexOfBatteryQuantity = CursorUtil.getColumnIndexOrThrow(_cursor, "batteryQuantity");
          final int _cursorIndexOfProblemReported = CursorUtil.getColumnIndexOrThrow(_cursor, "problemReported");
          final int _cursorIndexOfDefectsFound = CursorUtil.getColumnIndexOrThrow(_cursor, "defectsFound");
          final int _cursorIndexOfCorrectiveAction = CursorUtil.getColumnIndexOrThrow(_cursor, "correctiveAction");
          final int _cursorIndexOfRecommendations = CursorUtil.getColumnIndexOrThrow(_cursor, "recommendations");
          final int _cursorIndexOfTechniciansOnSite = CursorUtil.getColumnIndexOrThrow(_cursor, "techniciansOnSite");
          final int _cursorIndexOfStatusOfService = CursorUtil.getColumnIndexOrThrow(_cursor, "statusOfService");
          final int _cursorIndexOfCustomerSignaturePath = CursorUtil.getColumnIndexOrThrow(_cursor, "customerSignaturePath");
          final int _cursorIndexOfTechnicianSignaturePath = CursorUtil.getColumnIndexOrThrow(_cursor, "technicianSignaturePath");
          final int _cursorIndexOfTechnicianName = CursorUtil.getColumnIndexOrThrow(_cursor, "technicianName");
          final int _cursorIndexOfSignatureDateTime = CursorUtil.getColumnIndexOrThrow(_cursor, "signatureDateTime");
          final int _cursorIndexOfState = CursorUtil.getColumnIndexOrThrow(_cursor, "state");
          final int _cursorIndexOfSyncStatus = CursorUtil.getColumnIndexOrThrow(_cursor, "syncStatus");
          final int _cursorIndexOfSyncError = CursorUtil.getColumnIndexOrThrow(_cursor, "syncError");
          final ServiceReportEntity _result;
          if (_cursor.moveToFirst()) {
            final String _tmpLocalId;
            _tmpLocalId = _cursor.getString(_cursorIndexOfLocalId);
            final Long _tmpOdooId;
            if (_cursor.isNull(_cursorIndexOfOdooId)) {
              _tmpOdooId = null;
            } else {
              _tmpOdooId = _cursor.getLong(_cursorIndexOfOdooId);
            }
            final String _tmpMobileExternalId;
            _tmpMobileExternalId = _cursor.getString(_cursorIndexOfMobileExternalId);
            final Long _tmpJobId;
            if (_cursor.isNull(_cursorIndexOfJobId)) {
              _tmpJobId = null;
            } else {
              _tmpJobId = _cursor.getLong(_cursorIndexOfJobId);
            }
            final String _tmpReportNumber;
            _tmpReportNumber = _cursor.getString(_cursorIndexOfReportNumber);
            final Long _tmpCustomerId;
            if (_cursor.isNull(_cursorIndexOfCustomerId)) {
              _tmpCustomerId = null;
            } else {
              _tmpCustomerId = _cursor.getLong(_cursorIndexOfCustomerId);
            }
            final String _tmpCustomerName;
            _tmpCustomerName = _cursor.getString(_cursorIndexOfCustomerName);
            final String _tmpCompanyName;
            _tmpCompanyName = _cursor.getString(_cursorIndexOfCompanyName);
            final String _tmpContactName;
            _tmpContactName = _cursor.getString(_cursorIndexOfContactName);
            final String _tmpAddress;
            _tmpAddress = _cursor.getString(_cursorIndexOfAddress);
            final String _tmpServiceDate;
            _tmpServiceDate = _cursor.getString(_cursorIndexOfServiceDate);
            final String _tmpArrivalTime;
            _tmpArrivalTime = _cursor.getString(_cursorIndexOfArrivalTime);
            final String _tmpDepartureTime;
            _tmpDepartureTime = _cursor.getString(_cursorIndexOfDepartureTime);
            final double _tmpLaborHours;
            _tmpLaborHours = _cursor.getDouble(_cursorIndexOfLaborHours);
            final String _tmpVehicle;
            _tmpVehicle = _cursor.getString(_cursorIndexOfVehicle);
            final String _tmpPoReference;
            _tmpPoReference = _cursor.getString(_cursorIndexOfPoReference);
            final String _tmpServiceType;
            _tmpServiceType = _cursor.getString(_cursorIndexOfServiceType);
            final String _tmpOriginalReportNumber;
            _tmpOriginalReportNumber = _cursor.getString(_cursorIndexOfOriginalReportNumber);
            final String _tmpMake;
            _tmpMake = _cursor.getString(_cursorIndexOfMake);
            final String _tmpModel;
            _tmpModel = _cursor.getString(_cursorIndexOfModel);
            final String _tmpKva;
            _tmpKva = _cursor.getString(_cursorIndexOfKva);
            final String _tmpEquipmentType;
            _tmpEquipmentType = _cursor.getString(_cursorIndexOfEquipmentType);
            final String _tmpSerialNumber;
            _tmpSerialNumber = _cursor.getString(_cursorIndexOfSerialNumber);
            final String _tmpLoad;
            _tmpLoad = _cursor.getString(_cursorIndexOfLoad);
            final String _tmpInputVoltage;
            _tmpInputVoltage = _cursor.getString(_cursorIndexOfInputVoltage);
            final String _tmpOutputVoltage;
            _tmpOutputVoltage = _cursor.getString(_cursorIndexOfOutputVoltage);
            final boolean _tmpSystemDown;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfSystemDown);
            _tmpSystemDown = _tmp != 0;
            final String _tmpBatteryManufacturer;
            _tmpBatteryManufacturer = _cursor.getString(_cursorIndexOfBatteryManufacturer);
            final String _tmpBatteryType;
            _tmpBatteryType = _cursor.getString(_cursorIndexOfBatteryType);
            final String _tmpBatteryRating;
            _tmpBatteryRating = _cursor.getString(_cursorIndexOfBatteryRating);
            final int _tmpBatteryQuantity;
            _tmpBatteryQuantity = _cursor.getInt(_cursorIndexOfBatteryQuantity);
            final String _tmpProblemReported;
            _tmpProblemReported = _cursor.getString(_cursorIndexOfProblemReported);
            final String _tmpDefectsFound;
            _tmpDefectsFound = _cursor.getString(_cursorIndexOfDefectsFound);
            final String _tmpCorrectiveAction;
            _tmpCorrectiveAction = _cursor.getString(_cursorIndexOfCorrectiveAction);
            final String _tmpRecommendations;
            _tmpRecommendations = _cursor.getString(_cursorIndexOfRecommendations);
            final String _tmpTechniciansOnSite;
            _tmpTechniciansOnSite = _cursor.getString(_cursorIndexOfTechniciansOnSite);
            final String _tmpStatusOfService;
            _tmpStatusOfService = _cursor.getString(_cursorIndexOfStatusOfService);
            final String _tmpCustomerSignaturePath;
            _tmpCustomerSignaturePath = _cursor.getString(_cursorIndexOfCustomerSignaturePath);
            final String _tmpTechnicianSignaturePath;
            _tmpTechnicianSignaturePath = _cursor.getString(_cursorIndexOfTechnicianSignaturePath);
            final String _tmpTechnicianName;
            _tmpTechnicianName = _cursor.getString(_cursorIndexOfTechnicianName);
            final String _tmpSignatureDateTime;
            _tmpSignatureDateTime = _cursor.getString(_cursorIndexOfSignatureDateTime);
            final String _tmpState;
            _tmpState = _cursor.getString(_cursorIndexOfState);
            final String _tmpSyncStatus;
            _tmpSyncStatus = _cursor.getString(_cursorIndexOfSyncStatus);
            final String _tmpSyncError;
            _tmpSyncError = _cursor.getString(_cursorIndexOfSyncError);
            _result = new ServiceReportEntity(_tmpLocalId,_tmpOdooId,_tmpMobileExternalId,_tmpJobId,_tmpReportNumber,_tmpCustomerId,_tmpCustomerName,_tmpCompanyName,_tmpContactName,_tmpAddress,_tmpServiceDate,_tmpArrivalTime,_tmpDepartureTime,_tmpLaborHours,_tmpVehicle,_tmpPoReference,_tmpServiceType,_tmpOriginalReportNumber,_tmpMake,_tmpModel,_tmpKva,_tmpEquipmentType,_tmpSerialNumber,_tmpLoad,_tmpInputVoltage,_tmpOutputVoltage,_tmpSystemDown,_tmpBatteryManufacturer,_tmpBatteryType,_tmpBatteryRating,_tmpBatteryQuantity,_tmpProblemReported,_tmpDefectsFound,_tmpCorrectiveAction,_tmpRecommendations,_tmpTechniciansOnSite,_tmpStatusOfService,_tmpCustomerSignaturePath,_tmpTechnicianSignaturePath,_tmpTechnicianName,_tmpSignatureDateTime,_tmpState,_tmpSyncStatus,_tmpSyncError);
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Object reportForJob(final long jobId,
      final Continuation<? super ServiceReportEntity> $completion) {
    final String _sql = "select * from service_reports where jobId = ? limit 1";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, jobId);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<ServiceReportEntity>() {
      @Override
      @Nullable
      public ServiceReportEntity call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfLocalId = CursorUtil.getColumnIndexOrThrow(_cursor, "localId");
          final int _cursorIndexOfOdooId = CursorUtil.getColumnIndexOrThrow(_cursor, "odooId");
          final int _cursorIndexOfMobileExternalId = CursorUtil.getColumnIndexOrThrow(_cursor, "mobileExternalId");
          final int _cursorIndexOfJobId = CursorUtil.getColumnIndexOrThrow(_cursor, "jobId");
          final int _cursorIndexOfReportNumber = CursorUtil.getColumnIndexOrThrow(_cursor, "reportNumber");
          final int _cursorIndexOfCustomerId = CursorUtil.getColumnIndexOrThrow(_cursor, "customerId");
          final int _cursorIndexOfCustomerName = CursorUtil.getColumnIndexOrThrow(_cursor, "customerName");
          final int _cursorIndexOfCompanyName = CursorUtil.getColumnIndexOrThrow(_cursor, "companyName");
          final int _cursorIndexOfContactName = CursorUtil.getColumnIndexOrThrow(_cursor, "contactName");
          final int _cursorIndexOfAddress = CursorUtil.getColumnIndexOrThrow(_cursor, "address");
          final int _cursorIndexOfServiceDate = CursorUtil.getColumnIndexOrThrow(_cursor, "serviceDate");
          final int _cursorIndexOfArrivalTime = CursorUtil.getColumnIndexOrThrow(_cursor, "arrivalTime");
          final int _cursorIndexOfDepartureTime = CursorUtil.getColumnIndexOrThrow(_cursor, "departureTime");
          final int _cursorIndexOfLaborHours = CursorUtil.getColumnIndexOrThrow(_cursor, "laborHours");
          final int _cursorIndexOfVehicle = CursorUtil.getColumnIndexOrThrow(_cursor, "vehicle");
          final int _cursorIndexOfPoReference = CursorUtil.getColumnIndexOrThrow(_cursor, "poReference");
          final int _cursorIndexOfServiceType = CursorUtil.getColumnIndexOrThrow(_cursor, "serviceType");
          final int _cursorIndexOfOriginalReportNumber = CursorUtil.getColumnIndexOrThrow(_cursor, "originalReportNumber");
          final int _cursorIndexOfMake = CursorUtil.getColumnIndexOrThrow(_cursor, "make");
          final int _cursorIndexOfModel = CursorUtil.getColumnIndexOrThrow(_cursor, "model");
          final int _cursorIndexOfKva = CursorUtil.getColumnIndexOrThrow(_cursor, "kva");
          final int _cursorIndexOfEquipmentType = CursorUtil.getColumnIndexOrThrow(_cursor, "equipmentType");
          final int _cursorIndexOfSerialNumber = CursorUtil.getColumnIndexOrThrow(_cursor, "serialNumber");
          final int _cursorIndexOfLoad = CursorUtil.getColumnIndexOrThrow(_cursor, "load");
          final int _cursorIndexOfInputVoltage = CursorUtil.getColumnIndexOrThrow(_cursor, "inputVoltage");
          final int _cursorIndexOfOutputVoltage = CursorUtil.getColumnIndexOrThrow(_cursor, "outputVoltage");
          final int _cursorIndexOfSystemDown = CursorUtil.getColumnIndexOrThrow(_cursor, "systemDown");
          final int _cursorIndexOfBatteryManufacturer = CursorUtil.getColumnIndexOrThrow(_cursor, "batteryManufacturer");
          final int _cursorIndexOfBatteryType = CursorUtil.getColumnIndexOrThrow(_cursor, "batteryType");
          final int _cursorIndexOfBatteryRating = CursorUtil.getColumnIndexOrThrow(_cursor, "batteryRating");
          final int _cursorIndexOfBatteryQuantity = CursorUtil.getColumnIndexOrThrow(_cursor, "batteryQuantity");
          final int _cursorIndexOfProblemReported = CursorUtil.getColumnIndexOrThrow(_cursor, "problemReported");
          final int _cursorIndexOfDefectsFound = CursorUtil.getColumnIndexOrThrow(_cursor, "defectsFound");
          final int _cursorIndexOfCorrectiveAction = CursorUtil.getColumnIndexOrThrow(_cursor, "correctiveAction");
          final int _cursorIndexOfRecommendations = CursorUtil.getColumnIndexOrThrow(_cursor, "recommendations");
          final int _cursorIndexOfTechniciansOnSite = CursorUtil.getColumnIndexOrThrow(_cursor, "techniciansOnSite");
          final int _cursorIndexOfStatusOfService = CursorUtil.getColumnIndexOrThrow(_cursor, "statusOfService");
          final int _cursorIndexOfCustomerSignaturePath = CursorUtil.getColumnIndexOrThrow(_cursor, "customerSignaturePath");
          final int _cursorIndexOfTechnicianSignaturePath = CursorUtil.getColumnIndexOrThrow(_cursor, "technicianSignaturePath");
          final int _cursorIndexOfTechnicianName = CursorUtil.getColumnIndexOrThrow(_cursor, "technicianName");
          final int _cursorIndexOfSignatureDateTime = CursorUtil.getColumnIndexOrThrow(_cursor, "signatureDateTime");
          final int _cursorIndexOfState = CursorUtil.getColumnIndexOrThrow(_cursor, "state");
          final int _cursorIndexOfSyncStatus = CursorUtil.getColumnIndexOrThrow(_cursor, "syncStatus");
          final int _cursorIndexOfSyncError = CursorUtil.getColumnIndexOrThrow(_cursor, "syncError");
          final ServiceReportEntity _result;
          if (_cursor.moveToFirst()) {
            final String _tmpLocalId;
            _tmpLocalId = _cursor.getString(_cursorIndexOfLocalId);
            final Long _tmpOdooId;
            if (_cursor.isNull(_cursorIndexOfOdooId)) {
              _tmpOdooId = null;
            } else {
              _tmpOdooId = _cursor.getLong(_cursorIndexOfOdooId);
            }
            final String _tmpMobileExternalId;
            _tmpMobileExternalId = _cursor.getString(_cursorIndexOfMobileExternalId);
            final Long _tmpJobId;
            if (_cursor.isNull(_cursorIndexOfJobId)) {
              _tmpJobId = null;
            } else {
              _tmpJobId = _cursor.getLong(_cursorIndexOfJobId);
            }
            final String _tmpReportNumber;
            _tmpReportNumber = _cursor.getString(_cursorIndexOfReportNumber);
            final Long _tmpCustomerId;
            if (_cursor.isNull(_cursorIndexOfCustomerId)) {
              _tmpCustomerId = null;
            } else {
              _tmpCustomerId = _cursor.getLong(_cursorIndexOfCustomerId);
            }
            final String _tmpCustomerName;
            _tmpCustomerName = _cursor.getString(_cursorIndexOfCustomerName);
            final String _tmpCompanyName;
            _tmpCompanyName = _cursor.getString(_cursorIndexOfCompanyName);
            final String _tmpContactName;
            _tmpContactName = _cursor.getString(_cursorIndexOfContactName);
            final String _tmpAddress;
            _tmpAddress = _cursor.getString(_cursorIndexOfAddress);
            final String _tmpServiceDate;
            _tmpServiceDate = _cursor.getString(_cursorIndexOfServiceDate);
            final String _tmpArrivalTime;
            _tmpArrivalTime = _cursor.getString(_cursorIndexOfArrivalTime);
            final String _tmpDepartureTime;
            _tmpDepartureTime = _cursor.getString(_cursorIndexOfDepartureTime);
            final double _tmpLaborHours;
            _tmpLaborHours = _cursor.getDouble(_cursorIndexOfLaborHours);
            final String _tmpVehicle;
            _tmpVehicle = _cursor.getString(_cursorIndexOfVehicle);
            final String _tmpPoReference;
            _tmpPoReference = _cursor.getString(_cursorIndexOfPoReference);
            final String _tmpServiceType;
            _tmpServiceType = _cursor.getString(_cursorIndexOfServiceType);
            final String _tmpOriginalReportNumber;
            _tmpOriginalReportNumber = _cursor.getString(_cursorIndexOfOriginalReportNumber);
            final String _tmpMake;
            _tmpMake = _cursor.getString(_cursorIndexOfMake);
            final String _tmpModel;
            _tmpModel = _cursor.getString(_cursorIndexOfModel);
            final String _tmpKva;
            _tmpKva = _cursor.getString(_cursorIndexOfKva);
            final String _tmpEquipmentType;
            _tmpEquipmentType = _cursor.getString(_cursorIndexOfEquipmentType);
            final String _tmpSerialNumber;
            _tmpSerialNumber = _cursor.getString(_cursorIndexOfSerialNumber);
            final String _tmpLoad;
            _tmpLoad = _cursor.getString(_cursorIndexOfLoad);
            final String _tmpInputVoltage;
            _tmpInputVoltage = _cursor.getString(_cursorIndexOfInputVoltage);
            final String _tmpOutputVoltage;
            _tmpOutputVoltage = _cursor.getString(_cursorIndexOfOutputVoltage);
            final boolean _tmpSystemDown;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfSystemDown);
            _tmpSystemDown = _tmp != 0;
            final String _tmpBatteryManufacturer;
            _tmpBatteryManufacturer = _cursor.getString(_cursorIndexOfBatteryManufacturer);
            final String _tmpBatteryType;
            _tmpBatteryType = _cursor.getString(_cursorIndexOfBatteryType);
            final String _tmpBatteryRating;
            _tmpBatteryRating = _cursor.getString(_cursorIndexOfBatteryRating);
            final int _tmpBatteryQuantity;
            _tmpBatteryQuantity = _cursor.getInt(_cursorIndexOfBatteryQuantity);
            final String _tmpProblemReported;
            _tmpProblemReported = _cursor.getString(_cursorIndexOfProblemReported);
            final String _tmpDefectsFound;
            _tmpDefectsFound = _cursor.getString(_cursorIndexOfDefectsFound);
            final String _tmpCorrectiveAction;
            _tmpCorrectiveAction = _cursor.getString(_cursorIndexOfCorrectiveAction);
            final String _tmpRecommendations;
            _tmpRecommendations = _cursor.getString(_cursorIndexOfRecommendations);
            final String _tmpTechniciansOnSite;
            _tmpTechniciansOnSite = _cursor.getString(_cursorIndexOfTechniciansOnSite);
            final String _tmpStatusOfService;
            _tmpStatusOfService = _cursor.getString(_cursorIndexOfStatusOfService);
            final String _tmpCustomerSignaturePath;
            _tmpCustomerSignaturePath = _cursor.getString(_cursorIndexOfCustomerSignaturePath);
            final String _tmpTechnicianSignaturePath;
            _tmpTechnicianSignaturePath = _cursor.getString(_cursorIndexOfTechnicianSignaturePath);
            final String _tmpTechnicianName;
            _tmpTechnicianName = _cursor.getString(_cursorIndexOfTechnicianName);
            final String _tmpSignatureDateTime;
            _tmpSignatureDateTime = _cursor.getString(_cursorIndexOfSignatureDateTime);
            final String _tmpState;
            _tmpState = _cursor.getString(_cursorIndexOfState);
            final String _tmpSyncStatus;
            _tmpSyncStatus = _cursor.getString(_cursorIndexOfSyncStatus);
            final String _tmpSyncError;
            _tmpSyncError = _cursor.getString(_cursorIndexOfSyncError);
            _result = new ServiceReportEntity(_tmpLocalId,_tmpOdooId,_tmpMobileExternalId,_tmpJobId,_tmpReportNumber,_tmpCustomerId,_tmpCustomerName,_tmpCompanyName,_tmpContactName,_tmpAddress,_tmpServiceDate,_tmpArrivalTime,_tmpDepartureTime,_tmpLaborHours,_tmpVehicle,_tmpPoReference,_tmpServiceType,_tmpOriginalReportNumber,_tmpMake,_tmpModel,_tmpKva,_tmpEquipmentType,_tmpSerialNumber,_tmpLoad,_tmpInputVoltage,_tmpOutputVoltage,_tmpSystemDown,_tmpBatteryManufacturer,_tmpBatteryType,_tmpBatteryRating,_tmpBatteryQuantity,_tmpProblemReported,_tmpDefectsFound,_tmpCorrectiveAction,_tmpRecommendations,_tmpTechniciansOnSite,_tmpStatusOfService,_tmpCustomerSignaturePath,_tmpTechnicianSignaturePath,_tmpTechnicianName,_tmpSignatureDateTime,_tmpState,_tmpSyncStatus,_tmpSyncError);
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Object reportForOdooId(final long reportId,
      final Continuation<? super ServiceReportEntity> $completion) {
    final String _sql = "select * from service_reports where odooId = ? limit 1";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, reportId);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<ServiceReportEntity>() {
      @Override
      @Nullable
      public ServiceReportEntity call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfLocalId = CursorUtil.getColumnIndexOrThrow(_cursor, "localId");
          final int _cursorIndexOfOdooId = CursorUtil.getColumnIndexOrThrow(_cursor, "odooId");
          final int _cursorIndexOfMobileExternalId = CursorUtil.getColumnIndexOrThrow(_cursor, "mobileExternalId");
          final int _cursorIndexOfJobId = CursorUtil.getColumnIndexOrThrow(_cursor, "jobId");
          final int _cursorIndexOfReportNumber = CursorUtil.getColumnIndexOrThrow(_cursor, "reportNumber");
          final int _cursorIndexOfCustomerId = CursorUtil.getColumnIndexOrThrow(_cursor, "customerId");
          final int _cursorIndexOfCustomerName = CursorUtil.getColumnIndexOrThrow(_cursor, "customerName");
          final int _cursorIndexOfCompanyName = CursorUtil.getColumnIndexOrThrow(_cursor, "companyName");
          final int _cursorIndexOfContactName = CursorUtil.getColumnIndexOrThrow(_cursor, "contactName");
          final int _cursorIndexOfAddress = CursorUtil.getColumnIndexOrThrow(_cursor, "address");
          final int _cursorIndexOfServiceDate = CursorUtil.getColumnIndexOrThrow(_cursor, "serviceDate");
          final int _cursorIndexOfArrivalTime = CursorUtil.getColumnIndexOrThrow(_cursor, "arrivalTime");
          final int _cursorIndexOfDepartureTime = CursorUtil.getColumnIndexOrThrow(_cursor, "departureTime");
          final int _cursorIndexOfLaborHours = CursorUtil.getColumnIndexOrThrow(_cursor, "laborHours");
          final int _cursorIndexOfVehicle = CursorUtil.getColumnIndexOrThrow(_cursor, "vehicle");
          final int _cursorIndexOfPoReference = CursorUtil.getColumnIndexOrThrow(_cursor, "poReference");
          final int _cursorIndexOfServiceType = CursorUtil.getColumnIndexOrThrow(_cursor, "serviceType");
          final int _cursorIndexOfOriginalReportNumber = CursorUtil.getColumnIndexOrThrow(_cursor, "originalReportNumber");
          final int _cursorIndexOfMake = CursorUtil.getColumnIndexOrThrow(_cursor, "make");
          final int _cursorIndexOfModel = CursorUtil.getColumnIndexOrThrow(_cursor, "model");
          final int _cursorIndexOfKva = CursorUtil.getColumnIndexOrThrow(_cursor, "kva");
          final int _cursorIndexOfEquipmentType = CursorUtil.getColumnIndexOrThrow(_cursor, "equipmentType");
          final int _cursorIndexOfSerialNumber = CursorUtil.getColumnIndexOrThrow(_cursor, "serialNumber");
          final int _cursorIndexOfLoad = CursorUtil.getColumnIndexOrThrow(_cursor, "load");
          final int _cursorIndexOfInputVoltage = CursorUtil.getColumnIndexOrThrow(_cursor, "inputVoltage");
          final int _cursorIndexOfOutputVoltage = CursorUtil.getColumnIndexOrThrow(_cursor, "outputVoltage");
          final int _cursorIndexOfSystemDown = CursorUtil.getColumnIndexOrThrow(_cursor, "systemDown");
          final int _cursorIndexOfBatteryManufacturer = CursorUtil.getColumnIndexOrThrow(_cursor, "batteryManufacturer");
          final int _cursorIndexOfBatteryType = CursorUtil.getColumnIndexOrThrow(_cursor, "batteryType");
          final int _cursorIndexOfBatteryRating = CursorUtil.getColumnIndexOrThrow(_cursor, "batteryRating");
          final int _cursorIndexOfBatteryQuantity = CursorUtil.getColumnIndexOrThrow(_cursor, "batteryQuantity");
          final int _cursorIndexOfProblemReported = CursorUtil.getColumnIndexOrThrow(_cursor, "problemReported");
          final int _cursorIndexOfDefectsFound = CursorUtil.getColumnIndexOrThrow(_cursor, "defectsFound");
          final int _cursorIndexOfCorrectiveAction = CursorUtil.getColumnIndexOrThrow(_cursor, "correctiveAction");
          final int _cursorIndexOfRecommendations = CursorUtil.getColumnIndexOrThrow(_cursor, "recommendations");
          final int _cursorIndexOfTechniciansOnSite = CursorUtil.getColumnIndexOrThrow(_cursor, "techniciansOnSite");
          final int _cursorIndexOfStatusOfService = CursorUtil.getColumnIndexOrThrow(_cursor, "statusOfService");
          final int _cursorIndexOfCustomerSignaturePath = CursorUtil.getColumnIndexOrThrow(_cursor, "customerSignaturePath");
          final int _cursorIndexOfTechnicianSignaturePath = CursorUtil.getColumnIndexOrThrow(_cursor, "technicianSignaturePath");
          final int _cursorIndexOfTechnicianName = CursorUtil.getColumnIndexOrThrow(_cursor, "technicianName");
          final int _cursorIndexOfSignatureDateTime = CursorUtil.getColumnIndexOrThrow(_cursor, "signatureDateTime");
          final int _cursorIndexOfState = CursorUtil.getColumnIndexOrThrow(_cursor, "state");
          final int _cursorIndexOfSyncStatus = CursorUtil.getColumnIndexOrThrow(_cursor, "syncStatus");
          final int _cursorIndexOfSyncError = CursorUtil.getColumnIndexOrThrow(_cursor, "syncError");
          final ServiceReportEntity _result;
          if (_cursor.moveToFirst()) {
            final String _tmpLocalId;
            _tmpLocalId = _cursor.getString(_cursorIndexOfLocalId);
            final Long _tmpOdooId;
            if (_cursor.isNull(_cursorIndexOfOdooId)) {
              _tmpOdooId = null;
            } else {
              _tmpOdooId = _cursor.getLong(_cursorIndexOfOdooId);
            }
            final String _tmpMobileExternalId;
            _tmpMobileExternalId = _cursor.getString(_cursorIndexOfMobileExternalId);
            final Long _tmpJobId;
            if (_cursor.isNull(_cursorIndexOfJobId)) {
              _tmpJobId = null;
            } else {
              _tmpJobId = _cursor.getLong(_cursorIndexOfJobId);
            }
            final String _tmpReportNumber;
            _tmpReportNumber = _cursor.getString(_cursorIndexOfReportNumber);
            final Long _tmpCustomerId;
            if (_cursor.isNull(_cursorIndexOfCustomerId)) {
              _tmpCustomerId = null;
            } else {
              _tmpCustomerId = _cursor.getLong(_cursorIndexOfCustomerId);
            }
            final String _tmpCustomerName;
            _tmpCustomerName = _cursor.getString(_cursorIndexOfCustomerName);
            final String _tmpCompanyName;
            _tmpCompanyName = _cursor.getString(_cursorIndexOfCompanyName);
            final String _tmpContactName;
            _tmpContactName = _cursor.getString(_cursorIndexOfContactName);
            final String _tmpAddress;
            _tmpAddress = _cursor.getString(_cursorIndexOfAddress);
            final String _tmpServiceDate;
            _tmpServiceDate = _cursor.getString(_cursorIndexOfServiceDate);
            final String _tmpArrivalTime;
            _tmpArrivalTime = _cursor.getString(_cursorIndexOfArrivalTime);
            final String _tmpDepartureTime;
            _tmpDepartureTime = _cursor.getString(_cursorIndexOfDepartureTime);
            final double _tmpLaborHours;
            _tmpLaborHours = _cursor.getDouble(_cursorIndexOfLaborHours);
            final String _tmpVehicle;
            _tmpVehicle = _cursor.getString(_cursorIndexOfVehicle);
            final String _tmpPoReference;
            _tmpPoReference = _cursor.getString(_cursorIndexOfPoReference);
            final String _tmpServiceType;
            _tmpServiceType = _cursor.getString(_cursorIndexOfServiceType);
            final String _tmpOriginalReportNumber;
            _tmpOriginalReportNumber = _cursor.getString(_cursorIndexOfOriginalReportNumber);
            final String _tmpMake;
            _tmpMake = _cursor.getString(_cursorIndexOfMake);
            final String _tmpModel;
            _tmpModel = _cursor.getString(_cursorIndexOfModel);
            final String _tmpKva;
            _tmpKva = _cursor.getString(_cursorIndexOfKva);
            final String _tmpEquipmentType;
            _tmpEquipmentType = _cursor.getString(_cursorIndexOfEquipmentType);
            final String _tmpSerialNumber;
            _tmpSerialNumber = _cursor.getString(_cursorIndexOfSerialNumber);
            final String _tmpLoad;
            _tmpLoad = _cursor.getString(_cursorIndexOfLoad);
            final String _tmpInputVoltage;
            _tmpInputVoltage = _cursor.getString(_cursorIndexOfInputVoltage);
            final String _tmpOutputVoltage;
            _tmpOutputVoltage = _cursor.getString(_cursorIndexOfOutputVoltage);
            final boolean _tmpSystemDown;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfSystemDown);
            _tmpSystemDown = _tmp != 0;
            final String _tmpBatteryManufacturer;
            _tmpBatteryManufacturer = _cursor.getString(_cursorIndexOfBatteryManufacturer);
            final String _tmpBatteryType;
            _tmpBatteryType = _cursor.getString(_cursorIndexOfBatteryType);
            final String _tmpBatteryRating;
            _tmpBatteryRating = _cursor.getString(_cursorIndexOfBatteryRating);
            final int _tmpBatteryQuantity;
            _tmpBatteryQuantity = _cursor.getInt(_cursorIndexOfBatteryQuantity);
            final String _tmpProblemReported;
            _tmpProblemReported = _cursor.getString(_cursorIndexOfProblemReported);
            final String _tmpDefectsFound;
            _tmpDefectsFound = _cursor.getString(_cursorIndexOfDefectsFound);
            final String _tmpCorrectiveAction;
            _tmpCorrectiveAction = _cursor.getString(_cursorIndexOfCorrectiveAction);
            final String _tmpRecommendations;
            _tmpRecommendations = _cursor.getString(_cursorIndexOfRecommendations);
            final String _tmpTechniciansOnSite;
            _tmpTechniciansOnSite = _cursor.getString(_cursorIndexOfTechniciansOnSite);
            final String _tmpStatusOfService;
            _tmpStatusOfService = _cursor.getString(_cursorIndexOfStatusOfService);
            final String _tmpCustomerSignaturePath;
            _tmpCustomerSignaturePath = _cursor.getString(_cursorIndexOfCustomerSignaturePath);
            final String _tmpTechnicianSignaturePath;
            _tmpTechnicianSignaturePath = _cursor.getString(_cursorIndexOfTechnicianSignaturePath);
            final String _tmpTechnicianName;
            _tmpTechnicianName = _cursor.getString(_cursorIndexOfTechnicianName);
            final String _tmpSignatureDateTime;
            _tmpSignatureDateTime = _cursor.getString(_cursorIndexOfSignatureDateTime);
            final String _tmpState;
            _tmpState = _cursor.getString(_cursorIndexOfState);
            final String _tmpSyncStatus;
            _tmpSyncStatus = _cursor.getString(_cursorIndexOfSyncStatus);
            final String _tmpSyncError;
            _tmpSyncError = _cursor.getString(_cursorIndexOfSyncError);
            _result = new ServiceReportEntity(_tmpLocalId,_tmpOdooId,_tmpMobileExternalId,_tmpJobId,_tmpReportNumber,_tmpCustomerId,_tmpCustomerName,_tmpCompanyName,_tmpContactName,_tmpAddress,_tmpServiceDate,_tmpArrivalTime,_tmpDepartureTime,_tmpLaborHours,_tmpVehicle,_tmpPoReference,_tmpServiceType,_tmpOriginalReportNumber,_tmpMake,_tmpModel,_tmpKva,_tmpEquipmentType,_tmpSerialNumber,_tmpLoad,_tmpInputVoltage,_tmpOutputVoltage,_tmpSystemDown,_tmpBatteryManufacturer,_tmpBatteryType,_tmpBatteryRating,_tmpBatteryQuantity,_tmpProblemReported,_tmpDefectsFound,_tmpCorrectiveAction,_tmpRecommendations,_tmpTechniciansOnSite,_tmpStatusOfService,_tmpCustomerSignaturePath,_tmpTechnicianSignaturePath,_tmpTechnicianName,_tmpSignatureDateTime,_tmpState,_tmpSyncStatus,_tmpSyncError);
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Object pendingReports(final Continuation<? super List<ServiceReportEntity>> $completion) {
    final String _sql = "select * from service_reports where syncStatus in ('Pending Sync', 'Sync Failed', 'Syncing')";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<List<ServiceReportEntity>>() {
      @Override
      @NonNull
      public List<ServiceReportEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfLocalId = CursorUtil.getColumnIndexOrThrow(_cursor, "localId");
          final int _cursorIndexOfOdooId = CursorUtil.getColumnIndexOrThrow(_cursor, "odooId");
          final int _cursorIndexOfMobileExternalId = CursorUtil.getColumnIndexOrThrow(_cursor, "mobileExternalId");
          final int _cursorIndexOfJobId = CursorUtil.getColumnIndexOrThrow(_cursor, "jobId");
          final int _cursorIndexOfReportNumber = CursorUtil.getColumnIndexOrThrow(_cursor, "reportNumber");
          final int _cursorIndexOfCustomerId = CursorUtil.getColumnIndexOrThrow(_cursor, "customerId");
          final int _cursorIndexOfCustomerName = CursorUtil.getColumnIndexOrThrow(_cursor, "customerName");
          final int _cursorIndexOfCompanyName = CursorUtil.getColumnIndexOrThrow(_cursor, "companyName");
          final int _cursorIndexOfContactName = CursorUtil.getColumnIndexOrThrow(_cursor, "contactName");
          final int _cursorIndexOfAddress = CursorUtil.getColumnIndexOrThrow(_cursor, "address");
          final int _cursorIndexOfServiceDate = CursorUtil.getColumnIndexOrThrow(_cursor, "serviceDate");
          final int _cursorIndexOfArrivalTime = CursorUtil.getColumnIndexOrThrow(_cursor, "arrivalTime");
          final int _cursorIndexOfDepartureTime = CursorUtil.getColumnIndexOrThrow(_cursor, "departureTime");
          final int _cursorIndexOfLaborHours = CursorUtil.getColumnIndexOrThrow(_cursor, "laborHours");
          final int _cursorIndexOfVehicle = CursorUtil.getColumnIndexOrThrow(_cursor, "vehicle");
          final int _cursorIndexOfPoReference = CursorUtil.getColumnIndexOrThrow(_cursor, "poReference");
          final int _cursorIndexOfServiceType = CursorUtil.getColumnIndexOrThrow(_cursor, "serviceType");
          final int _cursorIndexOfOriginalReportNumber = CursorUtil.getColumnIndexOrThrow(_cursor, "originalReportNumber");
          final int _cursorIndexOfMake = CursorUtil.getColumnIndexOrThrow(_cursor, "make");
          final int _cursorIndexOfModel = CursorUtil.getColumnIndexOrThrow(_cursor, "model");
          final int _cursorIndexOfKva = CursorUtil.getColumnIndexOrThrow(_cursor, "kva");
          final int _cursorIndexOfEquipmentType = CursorUtil.getColumnIndexOrThrow(_cursor, "equipmentType");
          final int _cursorIndexOfSerialNumber = CursorUtil.getColumnIndexOrThrow(_cursor, "serialNumber");
          final int _cursorIndexOfLoad = CursorUtil.getColumnIndexOrThrow(_cursor, "load");
          final int _cursorIndexOfInputVoltage = CursorUtil.getColumnIndexOrThrow(_cursor, "inputVoltage");
          final int _cursorIndexOfOutputVoltage = CursorUtil.getColumnIndexOrThrow(_cursor, "outputVoltage");
          final int _cursorIndexOfSystemDown = CursorUtil.getColumnIndexOrThrow(_cursor, "systemDown");
          final int _cursorIndexOfBatteryManufacturer = CursorUtil.getColumnIndexOrThrow(_cursor, "batteryManufacturer");
          final int _cursorIndexOfBatteryType = CursorUtil.getColumnIndexOrThrow(_cursor, "batteryType");
          final int _cursorIndexOfBatteryRating = CursorUtil.getColumnIndexOrThrow(_cursor, "batteryRating");
          final int _cursorIndexOfBatteryQuantity = CursorUtil.getColumnIndexOrThrow(_cursor, "batteryQuantity");
          final int _cursorIndexOfProblemReported = CursorUtil.getColumnIndexOrThrow(_cursor, "problemReported");
          final int _cursorIndexOfDefectsFound = CursorUtil.getColumnIndexOrThrow(_cursor, "defectsFound");
          final int _cursorIndexOfCorrectiveAction = CursorUtil.getColumnIndexOrThrow(_cursor, "correctiveAction");
          final int _cursorIndexOfRecommendations = CursorUtil.getColumnIndexOrThrow(_cursor, "recommendations");
          final int _cursorIndexOfTechniciansOnSite = CursorUtil.getColumnIndexOrThrow(_cursor, "techniciansOnSite");
          final int _cursorIndexOfStatusOfService = CursorUtil.getColumnIndexOrThrow(_cursor, "statusOfService");
          final int _cursorIndexOfCustomerSignaturePath = CursorUtil.getColumnIndexOrThrow(_cursor, "customerSignaturePath");
          final int _cursorIndexOfTechnicianSignaturePath = CursorUtil.getColumnIndexOrThrow(_cursor, "technicianSignaturePath");
          final int _cursorIndexOfTechnicianName = CursorUtil.getColumnIndexOrThrow(_cursor, "technicianName");
          final int _cursorIndexOfSignatureDateTime = CursorUtil.getColumnIndexOrThrow(_cursor, "signatureDateTime");
          final int _cursorIndexOfState = CursorUtil.getColumnIndexOrThrow(_cursor, "state");
          final int _cursorIndexOfSyncStatus = CursorUtil.getColumnIndexOrThrow(_cursor, "syncStatus");
          final int _cursorIndexOfSyncError = CursorUtil.getColumnIndexOrThrow(_cursor, "syncError");
          final List<ServiceReportEntity> _result = new ArrayList<ServiceReportEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final ServiceReportEntity _item;
            final String _tmpLocalId;
            _tmpLocalId = _cursor.getString(_cursorIndexOfLocalId);
            final Long _tmpOdooId;
            if (_cursor.isNull(_cursorIndexOfOdooId)) {
              _tmpOdooId = null;
            } else {
              _tmpOdooId = _cursor.getLong(_cursorIndexOfOdooId);
            }
            final String _tmpMobileExternalId;
            _tmpMobileExternalId = _cursor.getString(_cursorIndexOfMobileExternalId);
            final Long _tmpJobId;
            if (_cursor.isNull(_cursorIndexOfJobId)) {
              _tmpJobId = null;
            } else {
              _tmpJobId = _cursor.getLong(_cursorIndexOfJobId);
            }
            final String _tmpReportNumber;
            _tmpReportNumber = _cursor.getString(_cursorIndexOfReportNumber);
            final Long _tmpCustomerId;
            if (_cursor.isNull(_cursorIndexOfCustomerId)) {
              _tmpCustomerId = null;
            } else {
              _tmpCustomerId = _cursor.getLong(_cursorIndexOfCustomerId);
            }
            final String _tmpCustomerName;
            _tmpCustomerName = _cursor.getString(_cursorIndexOfCustomerName);
            final String _tmpCompanyName;
            _tmpCompanyName = _cursor.getString(_cursorIndexOfCompanyName);
            final String _tmpContactName;
            _tmpContactName = _cursor.getString(_cursorIndexOfContactName);
            final String _tmpAddress;
            _tmpAddress = _cursor.getString(_cursorIndexOfAddress);
            final String _tmpServiceDate;
            _tmpServiceDate = _cursor.getString(_cursorIndexOfServiceDate);
            final String _tmpArrivalTime;
            _tmpArrivalTime = _cursor.getString(_cursorIndexOfArrivalTime);
            final String _tmpDepartureTime;
            _tmpDepartureTime = _cursor.getString(_cursorIndexOfDepartureTime);
            final double _tmpLaborHours;
            _tmpLaborHours = _cursor.getDouble(_cursorIndexOfLaborHours);
            final String _tmpVehicle;
            _tmpVehicle = _cursor.getString(_cursorIndexOfVehicle);
            final String _tmpPoReference;
            _tmpPoReference = _cursor.getString(_cursorIndexOfPoReference);
            final String _tmpServiceType;
            _tmpServiceType = _cursor.getString(_cursorIndexOfServiceType);
            final String _tmpOriginalReportNumber;
            _tmpOriginalReportNumber = _cursor.getString(_cursorIndexOfOriginalReportNumber);
            final String _tmpMake;
            _tmpMake = _cursor.getString(_cursorIndexOfMake);
            final String _tmpModel;
            _tmpModel = _cursor.getString(_cursorIndexOfModel);
            final String _tmpKva;
            _tmpKva = _cursor.getString(_cursorIndexOfKva);
            final String _tmpEquipmentType;
            _tmpEquipmentType = _cursor.getString(_cursorIndexOfEquipmentType);
            final String _tmpSerialNumber;
            _tmpSerialNumber = _cursor.getString(_cursorIndexOfSerialNumber);
            final String _tmpLoad;
            _tmpLoad = _cursor.getString(_cursorIndexOfLoad);
            final String _tmpInputVoltage;
            _tmpInputVoltage = _cursor.getString(_cursorIndexOfInputVoltage);
            final String _tmpOutputVoltage;
            _tmpOutputVoltage = _cursor.getString(_cursorIndexOfOutputVoltage);
            final boolean _tmpSystemDown;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfSystemDown);
            _tmpSystemDown = _tmp != 0;
            final String _tmpBatteryManufacturer;
            _tmpBatteryManufacturer = _cursor.getString(_cursorIndexOfBatteryManufacturer);
            final String _tmpBatteryType;
            _tmpBatteryType = _cursor.getString(_cursorIndexOfBatteryType);
            final String _tmpBatteryRating;
            _tmpBatteryRating = _cursor.getString(_cursorIndexOfBatteryRating);
            final int _tmpBatteryQuantity;
            _tmpBatteryQuantity = _cursor.getInt(_cursorIndexOfBatteryQuantity);
            final String _tmpProblemReported;
            _tmpProblemReported = _cursor.getString(_cursorIndexOfProblemReported);
            final String _tmpDefectsFound;
            _tmpDefectsFound = _cursor.getString(_cursorIndexOfDefectsFound);
            final String _tmpCorrectiveAction;
            _tmpCorrectiveAction = _cursor.getString(_cursorIndexOfCorrectiveAction);
            final String _tmpRecommendations;
            _tmpRecommendations = _cursor.getString(_cursorIndexOfRecommendations);
            final String _tmpTechniciansOnSite;
            _tmpTechniciansOnSite = _cursor.getString(_cursorIndexOfTechniciansOnSite);
            final String _tmpStatusOfService;
            _tmpStatusOfService = _cursor.getString(_cursorIndexOfStatusOfService);
            final String _tmpCustomerSignaturePath;
            _tmpCustomerSignaturePath = _cursor.getString(_cursorIndexOfCustomerSignaturePath);
            final String _tmpTechnicianSignaturePath;
            _tmpTechnicianSignaturePath = _cursor.getString(_cursorIndexOfTechnicianSignaturePath);
            final String _tmpTechnicianName;
            _tmpTechnicianName = _cursor.getString(_cursorIndexOfTechnicianName);
            final String _tmpSignatureDateTime;
            _tmpSignatureDateTime = _cursor.getString(_cursorIndexOfSignatureDateTime);
            final String _tmpState;
            _tmpState = _cursor.getString(_cursorIndexOfState);
            final String _tmpSyncStatus;
            _tmpSyncStatus = _cursor.getString(_cursorIndexOfSyncStatus);
            final String _tmpSyncError;
            _tmpSyncError = _cursor.getString(_cursorIndexOfSyncError);
            _item = new ServiceReportEntity(_tmpLocalId,_tmpOdooId,_tmpMobileExternalId,_tmpJobId,_tmpReportNumber,_tmpCustomerId,_tmpCustomerName,_tmpCompanyName,_tmpContactName,_tmpAddress,_tmpServiceDate,_tmpArrivalTime,_tmpDepartureTime,_tmpLaborHours,_tmpVehicle,_tmpPoReference,_tmpServiceType,_tmpOriginalReportNumber,_tmpMake,_tmpModel,_tmpKva,_tmpEquipmentType,_tmpSerialNumber,_tmpLoad,_tmpInputVoltage,_tmpOutputVoltage,_tmpSystemDown,_tmpBatteryManufacturer,_tmpBatteryType,_tmpBatteryRating,_tmpBatteryQuantity,_tmpProblemReported,_tmpDefectsFound,_tmpCorrectiveAction,_tmpRecommendations,_tmpTechniciansOnSite,_tmpStatusOfService,_tmpCustomerSignaturePath,_tmpTechnicianSignaturePath,_tmpTechnicianName,_tmpSignatureDateTime,_tmpState,_tmpSyncStatus,_tmpSyncError);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<PartEntity>> observeParts(final String localId) {
    final String _sql = "select * from parts where reportLocalId = ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindString(_argIndex, localId);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"parts"}, new Callable<List<PartEntity>>() {
      @Override
      @NonNull
      public List<PartEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfReportLocalId = CursorUtil.getColumnIndexOrThrow(_cursor, "reportLocalId");
          final int _cursorIndexOfPartName = CursorUtil.getColumnIndexOrThrow(_cursor, "partName");
          final int _cursorIndexOfSerialNumber = CursorUtil.getColumnIndexOrThrow(_cursor, "serialNumber");
          final int _cursorIndexOfQuantity = CursorUtil.getColumnIndexOrThrow(_cursor, "quantity");
          final int _cursorIndexOfConditionType = CursorUtil.getColumnIndexOrThrow(_cursor, "conditionType");
          final int _cursorIndexOfInvoiceable = CursorUtil.getColumnIndexOrThrow(_cursor, "invoiceable");
          final int _cursorIndexOfNotes = CursorUtil.getColumnIndexOrThrow(_cursor, "notes");
          final List<PartEntity> _result = new ArrayList<PartEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final PartEntity _item;
            final String _tmpId;
            _tmpId = _cursor.getString(_cursorIndexOfId);
            final String _tmpReportLocalId;
            _tmpReportLocalId = _cursor.getString(_cursorIndexOfReportLocalId);
            final String _tmpPartName;
            _tmpPartName = _cursor.getString(_cursorIndexOfPartName);
            final String _tmpSerialNumber;
            _tmpSerialNumber = _cursor.getString(_cursorIndexOfSerialNumber);
            final double _tmpQuantity;
            _tmpQuantity = _cursor.getDouble(_cursorIndexOfQuantity);
            final String _tmpConditionType;
            _tmpConditionType = _cursor.getString(_cursorIndexOfConditionType);
            final boolean _tmpInvoiceable;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfInvoiceable);
            _tmpInvoiceable = _tmp != 0;
            final String _tmpNotes;
            _tmpNotes = _cursor.getString(_cursorIndexOfNotes);
            _item = new PartEntity(_tmpId,_tmpReportLocalId,_tmpPartName,_tmpSerialNumber,_tmpQuantity,_tmpConditionType,_tmpInvoiceable,_tmpNotes);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Object partsForReport(final String localId,
      final Continuation<? super List<PartEntity>> $completion) {
    final String _sql = "select * from parts where reportLocalId = ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindString(_argIndex, localId);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<List<PartEntity>>() {
      @Override
      @NonNull
      public List<PartEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfReportLocalId = CursorUtil.getColumnIndexOrThrow(_cursor, "reportLocalId");
          final int _cursorIndexOfPartName = CursorUtil.getColumnIndexOrThrow(_cursor, "partName");
          final int _cursorIndexOfSerialNumber = CursorUtil.getColumnIndexOrThrow(_cursor, "serialNumber");
          final int _cursorIndexOfQuantity = CursorUtil.getColumnIndexOrThrow(_cursor, "quantity");
          final int _cursorIndexOfConditionType = CursorUtil.getColumnIndexOrThrow(_cursor, "conditionType");
          final int _cursorIndexOfInvoiceable = CursorUtil.getColumnIndexOrThrow(_cursor, "invoiceable");
          final int _cursorIndexOfNotes = CursorUtil.getColumnIndexOrThrow(_cursor, "notes");
          final List<PartEntity> _result = new ArrayList<PartEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final PartEntity _item;
            final String _tmpId;
            _tmpId = _cursor.getString(_cursorIndexOfId);
            final String _tmpReportLocalId;
            _tmpReportLocalId = _cursor.getString(_cursorIndexOfReportLocalId);
            final String _tmpPartName;
            _tmpPartName = _cursor.getString(_cursorIndexOfPartName);
            final String _tmpSerialNumber;
            _tmpSerialNumber = _cursor.getString(_cursorIndexOfSerialNumber);
            final double _tmpQuantity;
            _tmpQuantity = _cursor.getDouble(_cursorIndexOfQuantity);
            final String _tmpConditionType;
            _tmpConditionType = _cursor.getString(_cursorIndexOfConditionType);
            final boolean _tmpInvoiceable;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfInvoiceable);
            _tmpInvoiceable = _tmp != 0;
            final String _tmpNotes;
            _tmpNotes = _cursor.getString(_cursorIndexOfNotes);
            _item = new PartEntity(_tmpId,_tmpReportLocalId,_tmpPartName,_tmpSerialNumber,_tmpQuantity,_tmpConditionType,_tmpInvoiceable,_tmpNotes);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Object attachmentsForReport(final String localId,
      final Continuation<? super List<AttachmentEntity>> $completion) {
    final String _sql = "select * from attachments where reportLocalId = ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindString(_argIndex, localId);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<List<AttachmentEntity>>() {
      @Override
      @NonNull
      public List<AttachmentEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfReportLocalId = CursorUtil.getColumnIndexOrThrow(_cursor, "reportLocalId");
          final int _cursorIndexOfFilePath = CursorUtil.getColumnIndexOrThrow(_cursor, "filePath");
          final int _cursorIndexOfMimeType = CursorUtil.getColumnIndexOrThrow(_cursor, "mimeType");
          final int _cursorIndexOfCategory = CursorUtil.getColumnIndexOrThrow(_cursor, "category");
          final List<AttachmentEntity> _result = new ArrayList<AttachmentEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final AttachmentEntity _item;
            final String _tmpId;
            _tmpId = _cursor.getString(_cursorIndexOfId);
            final String _tmpReportLocalId;
            _tmpReportLocalId = _cursor.getString(_cursorIndexOfReportLocalId);
            final String _tmpFilePath;
            _tmpFilePath = _cursor.getString(_cursorIndexOfFilePath);
            final String _tmpMimeType;
            _tmpMimeType = _cursor.getString(_cursorIndexOfMimeType);
            final String _tmpCategory;
            _tmpCategory = _cursor.getString(_cursorIndexOfCategory);
            _item = new AttachmentEntity(_tmpId,_tmpReportLocalId,_tmpFilePath,_tmpMimeType,_tmpCategory);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
