#!/bin/bash

# 🛰️ Traccar Ubuntu "Perfect" Setup Script
# Version: 1.0.0
# Optimized for: Ubuntu 22.04 / 24.04 LTS
# Requirements: 4GB+ RAM, Root access

set -e
set -o pipefail

# --- Configuration ---
DB_NAME="traccar"
DB_USER="traccar"
DB_PASS=$(openssl rand -base64 12)
TRACCAR_REPO="https://github.com/traccar/traccar.git"
INSTALL_DIR="/opt/traccar"
LOG_DIR="/var/log/traccar"

echo "🚀 Starting Traccar Optimized Deployment..."

# 1. System Update & Dependencies
echo "📦 Updating system and installing dependencies..."
sudo apt-get update && sudo apt-get upgrade -y
sudo apt-get install -y openjdk-21-jdk git wget postgresql postgresql-contrib nginx unzip curl ufw

# 2. Database Setup
echo "🗄️ Configuring PostgreSQL..."
sudo -u postgres psql -c "CREATE USER $DB_USER WITH PASSWORD '$DB_PASS';"
sudo -u postgres psql -c "CREATE DATABASE $DB_NAME OWNER $DB_USER;"

# 3. Clone and Build Traccar
echo "📂 Cloning Traccar source code..."
sudo mkdir -p $INSTALL_DIR
sudo chown $USER:$USER $INSTALL_DIR
git clone --recursive $TRACCAR_REPO $INSTALL_DIR

cd $INSTALL_DIR
echo "🏗️ Building Traccar with Gradle..."
./gradlew assemble

# 4. Directory Structure & Permissions
echo "📁 Setting up directory structure..."
sudo mkdir -p $LOG_DIR
sudo mkdir -p $INSTALL_DIR/data
sudo chown -R $USER:$USER $INSTALL_DIR
sudo chown -R $USER:$USER $LOG_DIR

# 5. Configuration (Injecting Production XML)
echo "⚙️ Injecting production configuration..."
cat > $INSTALL_DIR/setup/traccar.xml <<EOF
<?xml version='1.0' encoding='UTF-8'?>
<!DOCTYPE properties SYSTEM 'http://java.sun.com/dtd/properties.dtd'>
<properties>
    <entry key='database.driver'>org.postgresql.Driver</entry>
    <entry key='database.url'>jdbc:postgresql://localhost:5432/$DB_NAME</entry>
    <entry key='database.user'>$DB_USER</entry>
    <entry key='database.password'>$DB_PASS</entry>

    <entry key='server.address'>0.0.0.0</entry>
    <entry key='web.port'>8082</entry>
    <entry key='web.path'>./modern</entry>
    
    <entry key='logger.file'>$LOG_DIR/tracker-server.log</entry>
    <entry key='logger.level'>info</entry>

    <!-- Performance Hardening -->
    <entry key='server.traffic.enable'>true</entry>
    <entry key='database.connectionPooling'>true</entry>
    <entry key='status.timeout'>120</entry>
</properties>
EOF

# 6. Systemd Service Setup
echo "🛠️ Creating systemd service..."
cat > /tmp/traccar.service <<EOF
[Unit]
Description=Traccar Service
After=network.target postgresql.service

[Service]
Type=simple
User=$USER
WorkingDirectory=$INSTALL_DIR
ExecStart=/usr/bin/java -Xms1G -Xmx2G -Djava.net.preferIPv4Stack=true -jar $INSTALL_DIR/target/tracker-server.jar $INSTALL_DIR/setup/traccar.xml
Restart=always
RestartSec=10
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
EOF
sudo mv /tmp/traccar.service /etc/systemd/system/traccar.service
sudo systemctl daemon-reload
sudo systemctl enable traccar

# 7. Nginx Setup (Reverse Proxy)
echo "🌐 Configuring Nginx Reverse Proxy..."
cat > /tmp/traccar_nginx <<EOF
server {
    listen 80;
    server_name _;

    location / {
        proxy_pass http://localhost:8082;
        proxy_http_version 1.1;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_read_timeout 86400s;
        proxy_send_timeout 86400s;
    }
}
EOF
sudo mv /tmp/traccar_nginx /etc/nginx/sites-available/traccar
sudo ln -sf /etc/nginx/sites-available/traccar /etc/nginx/sites-enabled/
sudo rm -f /etc/nginx/sites-enabled/default
sudo systemctl restart nginx

# 8. Firewall setup
echo "🛡️ Configuring Firewall..."
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw allow 5000:5150/tcp
sudo ufw allow 5000:5150/udp
sudo ufw --force enable

echo "---------------------------------------------------"
echo "✅ Traccar Deployment Complete!"
echo "📍 Access Web UI: http://YOUR_SERVER_IP"
echo "🔑 Database Password (Save this!): $DB_PASS"
echo "📝 Logs: tail -f $LOG_DIR/tracker-server.log"
echo "---------------------------------------------------"
