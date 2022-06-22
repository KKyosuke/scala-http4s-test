# websocket test

## connection test

### 単純に返却
```scala
val send: Stream[F, WebSocketFrame] = {
  Stream.emit(WebSocketFrame.Text("no message"))
}
```

```result
no message [time:1655891987212ms]
Disconnected!! [time:1655891989218ms]
```

6msで接続が切れる

### 定期的に返却
```scala
val send: Stream[F, WebSocketFrame] = {
  Stream.awakeEvery[F](2.seconds).map({ _ =>
    WebSocketFrame.Text("no message")
  })
}
```

```html
no message [time:1655892075867ms]
no message [time:1655892077863ms]
no message [time:1655892079859ms]
no message [time:1655892081859ms]
no message [time:1655892083860ms]
no message [time:1655892085859ms]
no message [time:1655892087859ms]
no message [time:1655892089860ms]
```
接続は保たれる

### 定期的に返却(限界値を調査)
[WebSocket の接続操作で既定されているタイムアウト値は 60 秒です。](https://docs.microsoft.com/ja-jp/windows/uwp/networking/websockets)実際にやってみた
```scala
val send: Stream[F, WebSocketFrame] = {
  Stream.awakeEvery[F](100.seconds).map({ _ =>
    WebSocketFrame.Text("no message")
  })
}
```

```result
Socket 接続成功 [time:1655892863238ms]
Disconnected!! [time:1655892923259ms]
sum [time:60020ms]
Socket 接続成功 [time:1655892923277ms]
Disconnected!! [time:1655892983298ms]
sum [time:60021ms]
Socket 接続成功 [time:1655892983316ms]
Disconnected!! [time:1655893043338ms]
sum [time:60022ms]
Socket 接続成功 [time:1655893043357ms]
Disconnected!! [time:1655893103377ms]
sum [time:60020ms]
Socket 接続成功 [time:1655893103395ms]
Disconnected!! [time:1655893163413ms]
sum [time:60018ms]
Socket 接続成功 [time:1655893163432ms]
Disconnected!! [time:1655893223444ms]
sum [time:60012ms]
```



### 定期的に返却(Poll)
```scala
val send: Stream[F, WebSocketFrame] = {
  val records = consumer.poll(Duration.ofSeconds(2L))
  var message = ""
  records.forEach({ record =>
    message += record.value
  })
  if (message.isEmpty) {
    Stream.emit(WebSocketFrame.Text("no message"))
  } else {
    Stream.emit(WebSocketFrame.Text(s"$message"))
  }
}
```

```html
Socket 接続成功 [time:1655893736743ms]
no message [time:1655893736751ms]
Disconnected!! [time:1655893738756ms]
sum [time:2013ms]
```
接続は切断される
