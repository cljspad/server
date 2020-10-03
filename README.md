# cljspad server

The server that hosts [cljspad](https://github.com/cljspad/cljspad)

## Running locally 

### 1) Compile and watch cljspad UI code 

```
git clone git@github.com:cljspad/cljspad.git
cd cljspad
./dev.sh
```

### 2) Run server code
``` 
git clone git@github.com:cljspad/server.git
cd server
lein repl
;; user=> (user/start! "/path/to/cljspad/ui/code")
```

### 3) Open UI

``` 
open http://localhost:3000
```
