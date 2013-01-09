package com.xtremelabs.imageutils;

public class NetworkInterfaceStub implements ImageNetworkInterface {

	@Override
	public void downloadImageToDisk(String url) {
	}

	@Override
	public void bump(String url) {
	}

	@Override
	public boolean isNetworkRequestPendingForUrl(String url) {
		return false;
	}

	@Override
	public void setNetworkRequestCreator(NetworkRequestCreator networkRequestImplementer) {
	}
}
