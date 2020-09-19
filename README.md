# cljsfiddle server

The server that hosts [cljsfiddle](https://github.com/cljsfiddle/cljsfiddle)

## Running locally 

### 1) Compile and watch cljsfiddle UI code 

```
git clone git@github.com:cljsfiddle/cljsfiddle.git
cd cljsfiddle
./dev.sh
```

### 2) Run server code
``` 
git clone git@github.com:cljsfiddle/server.git
cd server
lein repl
;; user=> (user/start! "/path/to/cljsfiddle/ui/code")
```

### 3) Open UI

``` 
open http://localhost:3000
```
