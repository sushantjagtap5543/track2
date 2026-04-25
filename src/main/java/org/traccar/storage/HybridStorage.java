package org.traccar.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.database.GoogleDriveService;
import org.traccar.model.BaseModel;
import org.traccar.model.Permission;
import org.traccar.model.Position;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;

@Singleton
public class HybridStorage extends Storage {

    private static final Logger LOGGER = LoggerFactory.getLogger(HybridStorage.class);

    private final DatabaseStorage databaseStorage;
    private final GoogleDriveService googleDriveService;
    private final Config config;
    private final ObjectMapper objectMapper;

    @Inject
    public HybridStorage(DatabaseStorage databaseStorage, GoogleDriveService googleDriveService, Config config, ObjectMapper objectMapper) {
        this.databaseStorage = databaseStorage;
        this.googleDriveService = googleDriveService;
        this.config = config;
        this.objectMapper = objectMapper;
    }

    @Override
    public <T> List<T> getObjects(Class<T> clazz, Request request) throws StorageException {
        if (clazz.equals(Position.class)) {
            try (var stream = getObjectsStream(clazz, request)) {
                return stream.toList();
            }
        }
        return databaseStorage.getObjects(clazz, request);
    }

    @Override
    public <T> Stream<T> getObjectsStream(Class<T> clazz, Request request) throws StorageException {
        if (clazz.equals(Position.class)) {
            Condition.Between between = findFixTimeBetween(request.getCondition());
            if (between != null) {
                Date fromDate = (Date) between.getFromValue();
                int retentionMonths = config.getInteger(Keys.DATABASE_RETENTION_MONTHS);
                Calendar calendar = Calendar.getInstance();
                calendar.add(Calendar.MONTH, -retentionMonths);
                Date cutoffDate = calendar.getTime();

                if (fromDate.before(cutoffDate)) {
                    long deviceId = findDeviceId(request.getCondition());
                    if (deviceId > 0) {
                        return Stream.concat(
                                fetchFromBackup(deviceId, fromDate, cutoffDate).stream(),
                                databaseStorage.getObjectsStream(clazz, request)
                        ).filter(p -> ((Position)p).getFixTime().after(fromDate)).map(clazz::cast);
                    }
                }
            }
        }
        return databaseStorage.getObjectsStream(clazz, request);
    }

    private <T> List<Position> fetchFromBackup(long deviceId, Date fromDate, Date toDate) {
        List<Position> results = new ArrayList<>();
        SimpleDateFormat monthFormat = new SimpleDateFormat("yyyy-MM");
        
        Calendar current = Calendar.getInstance();
        current.setTime(fromDate);
        
        while (current.getTime().before(toDate)) {
            String monthStr = monthFormat.format(current.getTime());
            try (InputStream in = googleDriveService.downloadFile(String.valueOf(deviceId), monthStr)) {
                if (in != null) {
                    results.addAll(parseCsv(in));
                }
            } catch (IOException e) {
                LOGGER.error("Error fetching backup for device " + deviceId + " month " + monthStr, e);
            }
            current.add(Calendar.MONTH, 1);
        }
        return results;
    }

    private List<Position> parseCsv(InputStream in) throws IOException {
        List<Position> positions = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
            String line = reader.readLine(); // skip header
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
                if (parts.length < 10) continue;

                Position p = new Position();
                p.setId(Long.parseLong(parts[0]));
                // Note: Parsing dates would require exact format used in write. 
                // Using SimpleDateFormat to match the toString() or a specific format.
                // For simplicity, I'll use a standard format in the task and here.
                SimpleDateFormat sdf = new SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy");
                try {
                   p.setFixTime(sdf.parse(parts[1]));
                   p.setDeviceTime(sdf.parse(parts[2]));
                } catch (Exception e) {
                    // Fallback or ignore
                }
                p.setLatitude(Double.parseDouble(parts[3]));
                p.setLongitude(Double.parseDouble(parts[4]));
                p.setAltitude(Double.parseDouble(parts[5]));
                p.setSpeed(Double.parseDouble(parts[6]));
                p.setCourse(Double.parseDouble(parts[7]));
                p.setAddress(parts[8].replaceAll("^\"|\"$", ""));
                String attrJson = parts[9].replaceAll("^\"|\"$", "").replace("'", "\"");
                p.setAttributes(objectMapper.readValue(attrJson, objectMapper.getTypeFactory().constructMapType(java.util.Map.class, String.class, Object.class)));
                positions.add(p);
            }
        }
        return positions;
    }

    private Condition.Between findFixTimeBetween(Condition condition) {
        if (condition instanceof Condition.Between between && between.getColumn().equals("fixTime")) {
            return between;
        }
        if (condition instanceof Condition.Binary binary) {
            Condition.Between found = findFixTimeBetween(binary.getFirst());
            if (found != null) return found;
            return findFixTimeBetween(binary.getSecond());
        }
        return null;
    }

    private long findDeviceId(Condition condition) {
        if (condition instanceof Condition.Equals equals && equals.getColumn().equals("deviceId")) {
            return (long) equals.getValue();
        }
        if (condition instanceof Condition.Binary binary) {
            long found = findDeviceId(binary.getFirst());
            if (found > 0) return found;
            return findDeviceId(binary.getSecond());
        }
        return -1;
    }

    @Override
    public <T> long addObject(T entity, Request request) throws StorageException {
        return databaseStorage.addObject(entity, request);
    }

    @Override
    public <T> void updateObject(T entity, Request request) throws StorageException {
        databaseStorage.updateObject(entity, request);
    }

    @Override
    public void removeObject(Class<?> clazz, Request request) throws StorageException {
        databaseStorage.removeObject(clazz, request);
    }

    @Override
    public List<Permission> getPermissions(Class<? extends BaseModel> ownerClass, long ownerId, Class<? extends BaseModel> propertyClass, long propertyId) throws StorageException {
        return databaseStorage.getPermissions(ownerClass, ownerId, propertyClass, propertyId);
    }

    @Override
    public void addPermission(Permission permission) throws StorageException {
        databaseStorage.addPermission(permission);
    }

    @Override
    public void removePermission(Permission permission) throws StorageException {
        databaseStorage.removePermission(permission);
    }
}
