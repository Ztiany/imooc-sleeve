import {Paging} from "../utils/paging";

const {Http} = require("../utils/http");

/**ๅๅๅ่กจ*/
class SpuPaging {

    static async getLatestPaging() {
        return new Paging({
            url: `spu/latest`
        }, 5)
    }

}

export {
    SpuPaging
}