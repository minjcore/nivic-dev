#!/bin/bash
# GtelPay Control Plane Server Startup Script

export JDBC_URL=jdbc:postgresql://localhost:5432/gtelpay_prod
export JDBC_USER=postgres
export JDBC_PASSWORD=password
export CONTROL_PLANE_PORT=8095

cd /Users/khangdc/Desktop/nivic-dev/java

echo "🚀 Starting GtelPay Control Plane..."
echo "📍 URL: http://localhost:8095"
echo "💾 Database: gtelpay_prod"
echo ""

mvn compile exec:java \
  -Dexec.mainClass=dev.nivic.coa.demo.ControlPlaneServer \
  -Dexec.args="" \
  2>&1 | tee ~/logs/nivic/server.log

