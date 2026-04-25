package org.traccar.database;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.FileContent;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.List;

@Singleton
public class GoogleDriveService {

    private static final Logger LOGGER = LoggerFactory.getLogger(GoogleDriveService.class);

    private final Drive drive;
    private final String folderId;

    @Inject
    public GoogleDriveService(Config config) throws GeneralSecurityException, IOException {
        String credentialsPath = config.getString(Keys.GOOGLE_DRIVE_CREDENTIALS_PATH);
        this.folderId = config.getString(Keys.GOOGLE_DRIVE_FOLDER_ID);

        if (credentialsPath != null) {
            InputStream in = new FileInputStream(credentialsPath);
            GoogleCredentials credentials = GoogleCredentials.fromStream(in)
                    .createScoped(Collections.singleton(DriveScopes.DRIVE_FILE));
            this.drive = new Drive.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    GsonFactory.getDefaultInstance(),
                    new HttpCredentialsAdapter(credentials))
                    .setApplicationName("Traccar")
                    .build();
        } else {
            this.drive = null;
            LOGGER.warn("Google Drive credentials path not configured");
        }
    }

    public String uploadFile(String name, java.io.File file, String parentFolderId) throws IOException {
        if (drive == null) {
            throw new IOException("Google Drive service not initialized");
        }
        File fileMetadata = new File();
        fileMetadata.setName(name);
        if (parentFolderId != null) {
            fileMetadata.setParents(Collections.singletonList(parentFolderId));
        } else if (folderId != null) {
            fileMetadata.setParents(Collections.singletonList(folderId));
        }

        FileContent mediaContent = new FileContent("text/csv", file);
        File uploadedFile = drive.files().create(fileMetadata, mediaContent)
                .setFields("id")
                .execute();
        return uploadedFile.getId();
    }

    public String getOrCreateFolder(String name, String parentFolderId) throws IOException {
        if (drive == null) {
            throw new IOException("Google Drive service not initialized");
        }
        FileList result = drive.files().list()
                .setQ("name = '" + name + "' and mimeType = 'application/vnd.google-apps.folder' and '" + parentFolderId + "' in parents and trashed = false")
                .setFields("files(id)")
                .execute();
        List<File> files = result.getFiles();
        if (files != null && !files.isEmpty()) {
            return files.get(0).getId();
        }

        File fileMetadata = new File();
        fileMetadata.setName(name);
        fileMetadata.setMimeType("application/vnd.google-apps.folder");
        fileMetadata.setParents(Collections.singletonList(parentFolderId));

        File folder = drive.files().create(fileMetadata)
                .setFields("id")
                .execute();
        return folder.getId();
    }

    public InputStream downloadFile(String deviceId, String month) throws IOException {
        if (drive == null) {
            throw new IOException("Google Drive service not initialized");
        }
        // Simplified search: deviceId/month.csv
        String deviceFolderId = getOrCreateFolder(deviceId, folderId);
        FileList result = drive.files().list()
                .setQ("name = '" + month + ".csv' and '" + deviceFolderId + "' in parents and trashed = false")
                .setFields("files(id)")
                .execute();
        
        List<File> files = result.getFiles();
        if (files == null || files.isEmpty()) {
            return null;
        }

        return drive.files().get(files.get(0).getId()).executeMediaAsInputStream();
    }
}
