import { afterEach, expect, it, vi } from "vitest";
import { financialFetch } from "./financial-fetch";
afterEach(() => vi.unstubAllGlobals());
it("keeps one key after network and server ambiguity then starts a new action after success", async () => {
 const fetchMock=vi.fn().mockRejectedValueOnce(new Error("network"))
  .mockResolvedValueOnce(new Response("",{status:503}))
  .mockResolvedValueOnce(new Response("{}",{status:200}))
  .mockResolvedValueOnce(new Response("{}",{status:200}));
 vi.stubGlobal("fetch",fetchMock);
 const url="/api/v1/investments/retry-fixture"; const init={method:"POST",body:'{"cost":"100"}'};
 await expect(financialFetch(url,init)).rejects.toThrow("network");
 await financialFetch(url,init);await financialFetch(url,init);await financialFetch(url,init);
 const keys=fetchMock.mock.calls.map(call=>new Headers(call[1].headers).get("Idempotency-Key"));
 expect(keys[0]).toBeTruthy();expect(keys[1]).toBe(keys[0]);expect(keys[2]).toBe(keys[0]);expect(keys[3]).not.toBe(keys[0]);
});
it("respects explicit keys and leaves reads and multipart uploads intact",async()=>{
 const fetchMock=vi.fn().mockResolvedValue(new Response("{}"));vi.stubGlobal("fetch",fetchMock);
 const explicit={method:"POST",body:"{}",headers:{"Idempotency-Key":"explicit-fixture"}};
 await financialFetch("/explicit",explicit);expect(fetchMock).toHaveBeenLastCalledWith("/explicit",explicit);
 await financialFetch("/read");expect(fetchMock).toHaveBeenLastCalledWith("/read",{});
 const upload={method:"POST",body:new FormData()};await financialFetch("/upload",upload);expect(fetchMock).toHaveBeenLastCalledWith("/upload",upload);
});
