const Vue = window.Vue
const vue = new Vue()
const prefix = 'store/api'

export default {
    getMemberList (params) {
        return vue.$ajax.get(`${prefix}/user/market/desk/store/member/list`, { params })
    },

    requestDeleteMember (params) {
        return vue.$ajax.delete(`${prefix}/user/market/desk/store/member/delete`, { params })
    },

    requestAddMember (params) {
        return vue.$ajax.post(`${prefix}/user/market/desk/store/member/add`, params)
    },

    requestChangeProject (params) {
        return vue.$ajax.put(`${prefix}/user/market/desk/store/member/test/project/change?projectCode=${params.projectCode}&storeCode=${params.storeCode}&storeType=${params.storeType}`)
    },

    getAllEnv ({ storeType, storeCode, scopes, varName }) {
        return vue.$ajax.get(`${prefix}/user/store/env/var/latest/types/${storeType}/codes/${storeCode}?scopes=${scopes}&varName=${varName}`)
    },

    getEnvChangeList ({ storeType, storeCode, varName }) {
        return vue.$ajax.get(`${prefix}/user/store/env/var/change/log/types/${storeType}/codes/${storeCode}/vars/${varName}`)
    },

    addEnv (params) {
        return vue.$ajax.post(`${prefix}/user/store/env/var/create`, params)
    },

    deleteEnv ({ storeType, storeCode, varNames }) {
        return vue.$ajax.delete(`${prefix}/user/store/env/var/types/${storeType}/codes/${storeCode}?varNames=${varNames}`)
    }
}
