#!/bin/bash
cd "$(dirname "$0")"
echo "=============================================="
echo "🚀 Deploying Remote Trackpad Website to Vercel"
echo "=============================================="
npx vercel --prod
