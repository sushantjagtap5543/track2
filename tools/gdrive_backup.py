import os
import subprocess
import datetime
import argparse
import sys

# Configuration (To be updated by user/environment)
DB_NAME = "traccar"
DB_USER = "traccar"
GDRIVE_FOLDER_ID = "1xR_DVXjm78URhz9gnbkOM1ERLARM-wN8"
BACKUP_DIR = "./backups"

def run_command(command):
    try:
        result = subprocess.run(command, shell=True, check=True, capture_output=True, text=True)
        return result.stdout
    except subprocess.CalledProcessError as e:
        print(f"Error executing command: {e}")
        print(f"Output: {e.output}")
        return None

def daily_backup():
    print(f"[{datetime.datetime.now()}] Starting daily backup...")
    timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    filename = f"traccar_full_{timestamp}.sql"
    filepath = os.path.join(BACKUP_DIR, filename)
    
    os.makedirs(BACKUP_DIR, exist_ok=True)
    
    # Run pg_dump
    # Note: Assumes PGPASSWORD is set in env or .pgpass is configured
    command = f"pg_dump -U {DB_USER} {DB_NAME} > {filepath}"
    if run_command(command) is not None:
        print(f"Backup created: {filepath}")
        upload_to_gdrive(filepath)
    else:
        print("Backup failed.")

def historical_archive():
    print(f"[{datetime.datetime.now()}] Archiving data older than 6 months...")
    six_months_ago = (datetime.datetime.now() - datetime.timedelta(days=180)).strftime("%Y-%m-%d")
    timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    filename = f"traccar_archive_pre_{six_months_ago}_{timestamp}.sql"
    filepath = os.path.join(BACKUP_DIR, filename)
    
    os.makedirs(BACKUP_DIR, exist_ok=True)
    
    # Query to extract historical positions and events
    query = f"COPY (SELECT * FROM tc_positions WHERE fixtime < '{six_months_ago}') TO STDOUT"
    command = f"psql -U {DB_USER} -d {DB_NAME} -c \"{query}\" > {filepath}"
    
    if run_command(command) is not None:
        print(f"Historical archive created: {filepath}")
        upload_to_gdrive(filepath)
    else:
        print("Archival failed.")

def upload_to_gdrive(filepath):
    """
    Placeholder for Google Drive Upload Logic.
    Requires google-api-python-client and service_account.json.
    """
    print(f"Ready to upload {filepath} to Google Drive folder {GDRIVE_FOLDER_ID}")
    print("ACTION REQUIRED: Provide service_account.json and install 'google-api-python-client' to enable automation.")
    
    # Example using rclone (common in production):
    # run_command(f"rclone copy {filepath} gdrive:{GDRIVE_FOLDER_ID}")

def fetch_backup(filename):
    print(f"Fetching {filename} from Google Drive...")
    # Placeholder for fetch logic
    print("ACTION REQUIRED: Implement fetch logic using Google Drive API or rclone.")

def main():
    parser = argparse.ArgumentParser(description="GeoSurePath Backup Utility")
    parser.add_argument("--mode", choices=["daily", "archive", "fetch"], required=True, help="Backup mode")
    parser.add_argument("--file", help="Filename for fetch mode")
    
    args = parser.parse_args()
    
    if args.mode == "daily":
        daily_backup()
    elif args.mode == "archive":
        historical_archive()
    elif args.mode == "fetch":
        if not args.file:
            print("Error: --file required for fetch mode")
        else:
            fetch_backup(args.file)

if __name__ == "__main__":
    main()
