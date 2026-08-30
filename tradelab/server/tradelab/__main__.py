import uvicorn

if __name__ == "__main__":
    # Keep the control-plane HTTP stack conservative behind Nginx.
    # On this VPS the auto-selected uvloop/httptools transport intermittently
    # reset loopback upstream connections before sending response headers,
    # producing Nginx 502s even for /health. The research recorder/strategy
    # asyncio tasks are unchanged; this only selects Uvicorn's HTTP transport.
    uvicorn.run(
        "tradelab.api:app",
        host="127.0.0.1",
        port=8000,
        reload=False,
        loop="asyncio",
        http="h11",
        timeout_keep_alive=5,
    )
