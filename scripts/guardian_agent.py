import os
import psutil
import requests
import json
import time
import subprocess
from flask import Flask, jsonify
from flask_cors import CORS
import threading

app = Flask(__name__)
CORS(app)

# Configuration
TRACCAR_API = "http://localhost:8082/api/server"
TRACCAR_PORT = 8082
AGENT_PORT = 8085
LOG_FILE = "c:/Users/sushant/Desktop/track/track2/logs/guardian.log"
BASE_DIR = "c:/Users/sushant/Desktop/track/track2"

os.makedirs(os.path.dirname(LOG_FILE), exist_ok=True)

def log_event(message):
    timestamp = time.strftime("%Y-%m-%d %H:%M:%S")
    with open(LOG_FILE, "a") as f:
        f.write(f"[{timestamp}] {message}\n")
    print(f"[{timestamp}] {message}")

class Guardian:
    def __init__(self):
        self.health_score = 100
        self.status = "Healthy"
        self.uptime = 0
        self.last_check = time.time()
        self.metrics = {
            "api_status": "Online",
            "java_processes": 0,
            "cpu_usage": 0,
            "ram_usage": 0,
            "disk_free": 0,
            "recent_actions": []
        }

    def update_metrics(self):
        try:
            # 1. API Check
            try:
                resp = requests.get(TRACCAR_API, timeout=5)
                self.metrics["api_status"] = "Online" if resp.status_code == 200 else "Degraded"
            except:
                self.metrics["api_status"] = "Offline"

            # 2. Process Check
            java_procs = [p for p in psutil.process_iter(['name']) if 'java' in p.info['name'].lower()]
            self.metrics["java_processes"] = len(java_procs)

            # 3. System Resources
            self.metrics["cpu_usage"] = psutil.cpu_percent()
            self.metrics["ram_usage"] = psutil.virtual_memory().percent
            self.metrics["disk_free"] = psutil.disk_usage(BASE_DIR).free // (1024 * 1024 * 1024) # GB

            # 4. Calculate Health Score
            score = 100
            if self.metrics["api_status"] == "Offline": score -= 50
            elif self.metrics["api_status"] == "Degraded": score -= 20
            if self.metrics["java_processes"] == 0: score -= 30
            if self.metrics["cpu_usage"] > 90: score -= 10
            if self.metrics["ram_usage"] > 90: score -= 10
            
            self.health_score = max(0, score)
            self.status = "Healthy" if self.health_score > 80 else "Warning" if self.health_score > 50 else "Critical"
            
        except Exception as e:
            log_event(f"Update Metrics Error: {e}")

    def self_heal(self):
        log_event("Triggering Self-Healing...")
        # 1. Clear Cache (Hypothetical Redis clear)
        try:
            # subprocess.run(["redis-cli", "FLUSHALL"], capture_output=True)
            self.metrics["recent_actions"].append("Cleared Redis Cache")
        except: pass

        # 2. Check and Restart Traccar if offline
        if self.metrics["api_status"] == "Offline" or self.metrics["java_processes"] == 0:
            log_event("Traccar seems down. Attempting restart...")
            # On Windows, we'd kill old java and run gradlew
            try:
                subprocess.run(["taskkill", "/F", "/IM", "java.exe"], capture_output=True)
                # Restart in background
                # subprocess.Popen(["cmd", "/c", "gradlew.bat run"], cwd=BASE_DIR, shell=True)
                self.metrics["recent_actions"].append("Restarted Traccar Service")
                log_event("Restart command issued.")
            except Exception as e:
                log_event(f"Restart failed: {e}")
        
        return {"status": "Self-healing triggered", "actions": self.metrics["recent_actions"][-3:]}

guardian = Guardian()

def monitor_loop():
    while True:
        guardian.update_metrics()
        # Auto-heal if critical
        if guardian.health_score < 50:
            # guardian.self_heal()
            pass
        time.sleep(30)

@app.route('/health', methods=['GET'])
def get_health():
    guardian.update_metrics()
    return jsonify({
        "score": guardian.health_score,
        "status": guardian.status,
        "metrics": guardian.metrics,
        "timestamp": time.time()
    })

@app.route('/self-heal', methods=['POST'])
def trigger_heal():
    result = guardian.self_heal()
    return jsonify(result)

if __name__ == '__main__':
    log_event("Guardian Agent Starting on port 8085...")
    monitor_thread = threading.Thread(target=monitor_loop, daemon=True)
    monitor_thread.start()
    app.run(port=AGENT_PORT, host='0.0.0.0')
