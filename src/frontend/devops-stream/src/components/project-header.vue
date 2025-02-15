<template>
    <header class="stream-header">
        <span class="header-info">
            <img class="ci-name" src="./../images/logo.svg" height="48" @click="goToHome" />

            <template v-if="$route.hash">
                <router-link
                    class="stream-link"
                    :to="{ name: 'pipeline' }"
                >
                    <icon
                        name="pipeline-link"
                        size="18"
                        class="link-icon"
                    ></icon>
                    Pipelines
                </router-link>
                <router-link
                    class="stream-link"
                    :to="{ name: 'metric' }"
                >
                    <icon
                        name="metric-link"
                        size="18"
                        class="link-icon"
                    ></icon>
                    Metrics
                </router-link>
                <router-link
                    class="stream-link"
                    :is="permission ? 'router-link' : 'span'"
                    :to="{ name: 'basicSetting' }"
                >
                    <icon
                        name="setting-link"
                        size="18"
                        class="link-icon"
                        v-bk-tooltips="{ content: $t('exception.permissionDeny'), disabled: permission }"
                    ></icon>
                    Settings
                </router-link>
            </template>
        </span>

        <section class="user-info">
            <bk-select
                behavior="simplicity"
                class="choose-project"
                searchable
                enable-scroll-load
                :clearable="false"
                :scroll-loading="bottomLoadingOptions"
                :value="projectInfo.id"
                @scroll-end="getProjectList"
                @selected="chooseProject"
            >
                <bk-option v-for="option in projectList"
                    :key="option.id"
                    :id="option.id"
                    :name="option.name">
                </bk-option>
            </bk-select>
            <toggle-language></toggle-language>
            <user
                class="user-info"
                :user="user"
                :message-num="messageNum"
            />
        </section>
    </header>
</template>

<script>
    import { mapActions, mapState } from 'vuex'
    import { common } from '@/http'
    import user from './user'
    import LINK_CONFIG from '@/conf/link-config.js'
    import toggleLanguage from './toggle-language.vue'

    export default ({
        name: 'StreamHeader',
        components: {
            user,
            toggleLanguage
        },
        data () {
            return {
                LINK_CONFIG,
                bottomLoadingOptions: {
                    size: 'small',
                    isLoading: true
                },
                projectList: [],
                pageInfo: {
                    page: 1,
                    pageSize: 20,
                    loadEnd: false
                }
            }
        },
        computed: {
            ...mapState(['exceptionInfo', 'projectInfo', 'user', 'permission', 'messageNum'])
        },
        created () {
            this.getUserInfo()
            this.getProjectList()
        },
        methods: {
            ...mapActions(['setUser', 'setExceptionInfo']),

            getProjectList () {
                if (this.pageInfo.loadEnd) {
                    return
                }
                this.bottomLoadingOptions.isLoading = true
                return common.getStreamProjects('MY_PROJECT', this.pageInfo.page, this.pageInfo.pageSize, '').then((res) => {
                    this.pageInfo.loadEnd = !res.hasNext
                    this.pageInfo.page = 1 + this.pageInfo.page
                    this.projectList.push(...res.records)
                }).finally(() => {
                    this.bottomLoadingOptions.isLoading = false
                })
            },

            chooseProject (id) {
                const url = new URL(location.href)
                url.hash = `#${id}`
                location.href = url
                location.reload()
            },

            getUserInfo () {
                return common.getUserInfo().then((userInfo = {}) => {
                    this.setUser(userInfo)
                })
            },

            goToHome () {
                this.setExceptionInfo({ type: 200 })
                this.$router.push({
                    name: 'dashboard'
                })
            }
        }
    })
</script>

<style lang="postcss" scoped>
    .stream-header {
        height: 61px;
        padding: 0 20px 0 10px;
        background: #182132;
        /* border-bottom: 1px solid #dde4eb; */
        display: flex;
        align-items: center;
        justify-content: space-between;
        color: #f5f7fa;
        .header-info {
            display: flex;
            justify-content: center;
            align-items: center;
            .ci-name {
                display: inline-block;
                margin: 0 121px 0 12px;
                font-size: 16px;
                color: #f5f7fa;
                cursor: pointer;
            }
            .stream-link {
                display: flex;
                align-items: center;
                line-height: 22px;
                margin-right: 32px;
                cursor: pointer;
                color: #96A2B9;
                &.router-link-active {
                    color: #fff;
                }
                .link-icon {
                    margin-right: 4px;
                }
            }
        }
    }

    .bk-dropdown-list li {
        min-width: 65px;
        position: relative;
    }
    .unread:before {
        content: '';
        position: absolute;
        right: 16px;
        top: calc(50% - 3px);
        width: 8px;
        height: 8px;
        border-radius: 100px;
        background: #ff5656;
    }

    .dropdown-trigger-btn {
        cursor: pointer;
        font-size: 14px;
        .name {
            color: #f5f7fa;
            margin: 0 8px;
        }
    }

    .user-info {
        display: flex;
        align-items: center;
        a {
            color: #c3cdd7;
            margin-top: 3px;
            margin-right: 8px;
        }
        a:hover {
            color: #fff;
        }
    }
    
    .choose-project {
        width: 250px;
        margin-right: 25px;
        /deep/ .bk-select-name {
            color: #fff;
        }
    }

    .navigation-header {
        -webkit-box-flex:1;
        -ms-flex:1;
        flex:1;
        height:100%;
        display:-webkit-box;
        display:-ms-flexbox;
        display:flex;
        -webkit-box-align:center;
        -ms-flex-align:center;
        align-items:center;
        font-size:14px;
        margin-left: 100px;
    }
    .navigation-header .header-nav {
        display:-webkit-box;
        display:-ms-flexbox;
        display:flex;
        padding:0;
        margin:0;
    }
    .navigation-header .header-nav-item {
        list-style:none;
        height:50px;
        display:-webkit-box;
        display:-ms-flexbox;
        display:flex;
        -webkit-box-align:center;
        -ms-flex-align:center;
        align-items:center;
        margin-right:40px;
        color:#96A2B9;
        min-width:56px
    }
    .navigation-header .header-nav-item.item-active {
        color:#FFFFFF !important;
    }
    .navigation-header .header-nav-item:hover {
        cursor:pointer;
        color:#D3D9E4;
    }
</style>
