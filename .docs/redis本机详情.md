To start redis now and restart at login:
brew services start redis
Or, if you don't want/need a background service you can just run:
/opt/homebrew/opt/redis/bin/redis-server /opt/homebrew/etc/redis.conf