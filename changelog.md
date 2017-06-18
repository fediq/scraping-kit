### 0.5.0 (2017.06.18)
* PageRef model changed - variable HTTP methods and depth increment
* HtmlCrawler SPI changed - explicit URI provision
* Links Queue API changed - drowning functionality removed
* Redis-based links queue and links history
* Kafka feeds exporter
* Redirect filtering
* Bugfixes

### 0.4.2 (2017.05.31)
* Throttling pulls in QueueingActor

### 0.4.1 (2017.05.30)
* RMQ queue bucketing added
* Shuffling removed from RMQ FIFO queue

### 0.4.0 (2017.05.29)
* Caching requests with errors (PageCache API changed)
* RMQ queue shuffling support, experimental (removed in 0.4.1)
* Chaining RMQ requests instead of paralleling
* JsonLinesFeedExporter now appends new lines instead of rewriting
* FileSystemPageCache paralleled
* Download errors logging severity decreased
* Proper URIs merging in DownloadingActor

### 0.3.0 (2017.05.26)
* Baseline release
