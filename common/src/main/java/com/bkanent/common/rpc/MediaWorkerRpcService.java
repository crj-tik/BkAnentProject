package com.bkanent.common.rpc;

import java.util.List;

public interface MediaWorkerRpcService {

    List<String> generateAssets(Long listingId, String prompt);
}
