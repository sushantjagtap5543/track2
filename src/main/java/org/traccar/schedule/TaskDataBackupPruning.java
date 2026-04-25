package org.traccar.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.database.GoogleDriveService;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TaskDataBackupPruning implements ScheduleTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskDataBackupPruning.class);

    private final Config config;
    private final Storage storage;
    private final GoogleDriveService googleDriveService;
    private final ObjectMapper objectMapper;

    @Inject
    public TaskDataBackupPruning(Config config, Storage storage, GoogleDriveService googleDriveService, ObjectMapper objectMapper) {
        this.config = config;
        this.storage = storage;
        this.googleDriveService = googleDriveService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void schedule(ScheduledExecutorService executor) {
        executor.scheduleWithFixedDelay(this, 1, 30 * 24 * 60, TimeUnit.MINUTES); // Every 30 days
    }

    @Override
    public void run() {
        int retentionMonths = config.getInteger(Keys.DATABASE_RETENTION_MONTHS);
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.MONTH, -retentionMonths);
        Date cutoffDate = calendar.getTime();

        SimpleDateFormat monthFormat = new SimpleDateFormat("yyyy-MM");
        String monthStr = monthFormat.format(cutoffDate);

        try {
            List<Device> devices = storage.getObjects(Device.class, new Request(new Columns.All()));
            for (Device device : devices) {
                backupAndPruneDevice(device, cutoffDate, monthStr);
            }
        } catch (StorageException e) {
            LOGGER.error("Error fetching devices for pruning", e);
        }
    }

    private void backupAndPruneDevice(Device device, Date cutoffDate, String monthStr) {
        try {
            Condition condition = new Condition.Binary(
                    new Condition.Equals("deviceId", device.getId()),
                    new Condition.Compare("fixTime", "<", cutoffDate),
                    "AND");
            
            List<Position> positions = storage.getObjects(Position.class, new Request(new Columns.All(), condition));
            if (positions.isEmpty()) {
                return;
            }

            File tempFile = Files.createTempFile("backup-" + device.getId() + "-" + monthStr, ".csv").toFile();
            try (FileWriter writer = new FileWriter(tempFile)) {
                writer.write("id,fixtime,devicetime,latitude,longitude,altitude,speed,course,address,attributes\n");
                for (Position p : positions) {
                    writer.write(String.format("%d,%s,%s,%f,%f,%f,%f,%f,\"%s\",\"%s\"\n",
                            p.getId(), p.getFixTime(), p.getDeviceTime(), p.getLatitude(), p.getLongitude(),
                            p.getAltitude(), p.getSpeed(), p.getCourse(),
                            p.getAddress() != null ? p.getAddress().replace("\"", "'") : "",
                            objectMapper.writeValueAsString(p.getAttributes()).replace("\"", "'")));
                }
            }

            String deviceFolderId = googleDriveService.getOrCreateFolder(String.valueOf(device.getId()), config.getString(Keys.GOOGLE_DRIVE_FOLDER_ID));
            googleDriveService.uploadFile(monthStr + ".csv", tempFile, deviceFolderId);

            storage.removeObject(Position.class, new Request(condition));
            LOGGER.info("Successfully backed up and pruned data for device {} up to {}", device.getId(), cutoffDate);

            Files.deleteIfExists(tempFile.toPath());
        } catch (Exception e) {
            LOGGER.error("Error backing up/pruning data for device " + device.getId(), e);
        }
    }
}
