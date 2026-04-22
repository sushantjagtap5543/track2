import os
import subprocess
import datetime
import argparse

# Configuration
DB_NAME = "traccar"
DB_USER = "traccar"

def run_query(query):
    command = f"psql -U {DB_USER} -d {DB_NAME} -t -c \"{query}\""
    try:
        result = subprocess.run(command, shell=True, check=True, capture_output=True, text=True)
        return result.stdout.strip()
    except subprocess.CalledProcessError as e:
        print(f"Query Error: {e}")
        return None

def check_gps_jumps():
    print("--- GPS Jump Audit ---")
    # Identify positions where speed is > 150 knots but logic suggests a jump
    query = "SELECT count(*) FROM tc_positions WHERE speed > 150 AND attributes::jsonb ->> 'motion' = 'true'"
    count = run_query(query)
    print(f"High-Speed Anomalies (Potential Jumps): {count}")

def check_offline_orphans():
    print("--- Connectivity Audit ---")
    query = "SELECT count(*) FROM tc_devices WHERE status = 'offline' AND lastupdate < NOW() - INTERVAL '30 days'"
    count = run_query(query)
    print(f"Orphaned Devices (Offline > 30 days): {count}")

def generate_governance_report():
    print(f"GeoSurePath Data Governance Report - {datetime.datetime.now()}")
    print("="*50)
    check_gps_jumps()
    check_offline_orphans()
    print("="*50)
    print("Recommendation: Enable strict filtering in traccar.xml to reduce these counts.")

def main():
    parser = argparse.ArgumentParser(description="GeoSurePath Data Governance Audit Tool")
    parser.add_argument("--report", action="store_true", help="Generate governance report")
    
    args = parser.parse_args()
    
    if args.report:
        generate_governance_report()
    else:
        parser.print_help()

if __name__ == "__main__":
    main()
